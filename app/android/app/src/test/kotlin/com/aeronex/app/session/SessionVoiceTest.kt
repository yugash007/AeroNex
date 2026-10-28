package com.aeronex.app.session

import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.speech.SpeechAlternative
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speech wired into the session (T12): PTT → recognizer → Kotlin parser → supervisor, STOP ends PTT, every span traced. */
@Suppress("UNCHECKED_CAST")
class SessionVoiceTest {
    private val vehicle = FakeArduCopter()
    private val engines = ScriptedEngines()
    private val captures = QueueCaptures()
    private val microphoneRequests = AtomicInteger()
    private val controller = SessionController(
        transports = { TransportLease(vehicle.transport()) },
        timingFor = { FAST_TIMING },
        voice = VoiceConfig(captures, engines, language = "en"),
    ).also { it.microphoneNeeded = { microphoneRequests.incrementAndGet() } }
    private val config = SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0)

    @After fun tearDown() {
        controller.close()
        vehicle.close()
    }

    private fun snapshot() = controller.snapshot()
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
    private fun speech() = snapshot().obj("speech")
    private val mic: QueuePcm get() = captures.source

    private fun connect() {
        controller.connect(config)
        waitFor("link up with a ready vehicle") {
            val s = snapshot()
            s.obj("link")["state"] == "up" && s.obj("vehicle").let { it["connected"] == true && it["positionReady"] == true }
        }
        controller.startVoice()
        waitFor("voice ready") { speech()["state"] == "READY" }
        captured { mic.silence(30) }
    }

    private fun captured(speak: () -> Unit) {
        speak()
        waitFor("frames captured") { captures.capture.ring.framesWritten == mic.queued.get() }
    }

    private fun say(vararg heard: SpeechAlternative) {
        engines.engine.result = heard.toList()
        controller.pttDown()
        captured { mic.speech(30) }
        controller.pttUp()
        captured { mic.silence(10) }
    }

    @Test fun aSpokenMotionCommandWaitsForConfirmWithThePhoneSource() {
        connect()
        say(alternative("take off to 3 m"))
        waitFor("pending plan") { snapshot()["pending"] != null }
        val pending = snapshot().obj("pending")
        assertEquals("phone", pending["source"])
        assertEquals("1. Take off to 3 m", pending["summary"])
        val speech = speech()
        assertEquals("take off to 3 m", speech["heard"])
        assertNull(speech["error"])
        assertEquals("OK", speech["level"])
        assertEquals("VOSK_GRAMMAR", speech["engine"])
    }

    @Test fun spokenHoldAndStopRunAtOnceWithoutConfirmation() {
        connect()
        say(alternative("hold position"))
        waitFor("HOLD ran") { snapshot()["run"] != null }
        assertNull(snapshot()["pending"])
        assertEquals("phone", snapshot().obj("run")["source"])
        say(alternative("stop"))
        waitFor("second immediate") { engines.engine.finishes.get() == 2 }
        waitFor("decided") { speech()["state"] == "READY" }
        assertNull(snapshot()["pending"])
        assertNull(speech()["error"])
    }

    @Test fun aStopKeyDuringPttDiscardsTheUtteranceAndTheLateReleaseSendsNothing() {
        connect()
        engines.engine.result = listOf(alternative("take off to 3 m"))
        controller.pttDown()
        captured { mic.speech(20) }
        controller.stop(StopTrigger.VOLUME_DOWN)
        waitFor("utterance cancelled") { engines.engine.cancels.get() == 1 }
        assertEquals(false, speech()["pttHeld"])
        controller.pttUp()
        captured { mic.silence(20) }
        assertEquals(0, engines.engine.finishes.get())
        assertNull(snapshot()["pending"])
        waitFor("STOP decided") { controller.supervisorState().stop != null }
        assertEquals(StopTrigger.VOLUME_DOWN, controller.supervisorState().stop!!.trigger)
    }

    @Test fun theStopButtonDuringTheTailAlsoDiscardsIt() {
        connect()
        engines.engine.result = listOf(alternative("take off to 3 m"))
        controller.pttDown()
        captured { mic.speech(20) }
        controller.pttUp()
        controller.stop(StopTrigger.BUTTON)
        captured { mic.silence(20) }
        Thread.sleep(100)
        assertNull("a plan never appears after the STOP", snapshot()["pending"])
        assertTrue(controller.trace.events().none { it.name == SessionController.PLAN_SUBMITTED })
    }

    @Test fun pressingTalkDropsAWaitingPlanButNeverStopsARunningOne() {
        connect()
        assertNotNull(controller.submitText("take off to 3 m", "en")["token"])
        controller.pttDown()
        waitFor("pending dropped") { snapshot()["pending"] == null }
        assertEquals(SafetySupervisor.CANCELLED, snapshot()["notice"])
        assertNull("no STOP", controller.supervisorState().stop)
        controller.pttUp()
    }

    @Test fun everyLatencySpanOfTheVoicePathIsTraced() {
        connect()
        assertTrue(controller.setExecution(true))
        say(alternative("take off to 3 m"))
        waitFor("pending plan") { snapshot()["pending"] != null }
        val token = snapshot().obj("pending")["token"] as String
        assertEquals(mapOf("accepted" to true), controller.confirm(token))
        waitFor("airborne", 10_000) { snapshot().obj("vehicle")["landedStateName"] == "IN_AIR" }
        waitFor("spotter armed") { speech()["spotterArmed"] == true }
        controller.stop(StopTrigger.BUTTON)
        waitFor("stop frame") { (snapshot().obj("latency"))["stopToTx"] != null }
        val latency = snapshot().obj("latency")
        for (stage in listOf("pttUpToDrained", "drainedToFinal", "pttUpToFinal", "finalToPlan", "pttUpToPlan",
            "confirmToFrame", "stopToTx")) {
            assertNotNull(stage, latency[stage])
        }
    }

    @Test fun theSpotterIsOffOnTheGroundAndTheMicrophoneIsAskedForWhenTalkIsFirstUsed() {
        controller.connect(config)
        waitFor("link up") { snapshot().obj("link")["state"] == "up" }
        assertEquals("OFF", speech()["state"])
        controller.pttDown()
        assertEquals(1, microphoneRequests.get())
        controller.pttUp()
        controller.startVoice()
        waitFor("voice ready") { speech()["state"] == "READY" }
        assertEquals(false, speech()["spotterArmed"])
        controller.disconnect()
        assertEquals("OFF", speech()["state"])
        assertFalse(captures.capture.running)
    }

    @Test fun theLanguageIsSetForVoiceAndChecked() {
        assertEquals("te", controller.setLanguage("te-IN"))
        assertEquals("te", speech()["language"])
        try {
            controller.setLanguage("xx")
            throw AssertionError("accepted")
        } catch (e: SessionException) {
            assertEquals(SessionException.BAD_ARGS, e.code)
        }
    }
}
