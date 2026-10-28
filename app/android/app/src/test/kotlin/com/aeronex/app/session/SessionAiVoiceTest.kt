package com.aeronex.app.session

import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A transcript the rules do not understand goes to the AI path too; the speech thread never waits for it. */
@Suppress("UNCHECKED_CAST")
class SessionAiVoiceTest {
    private val vehicle = FakeArduCopter()
    private val engines = ScriptedEngines()
    private val captures = QueueCaptures()
    private val ai = FakeAi()
    private val controller = SessionController(
        transports = { TransportLease(vehicle.transport()) },
        timingFor = { FAST_TIMING },
        voice = VoiceConfig(captures, engines, language = "en"),
        ai = ai.config,
    )

    @After fun tearDown() {
        ai.runtime.hold?.countDown()
        controller.close()
        vehicle.close()
    }

    private fun snapshot() = controller.snapshot()
    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
    private val mic: QueuePcm get() = captures.source

    private fun connect() {
        controller.connect(SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0))
        waitFor("link up with a ready vehicle") {
            val s = snapshot()
            s.obj("link")["state"] == "up" && s.obj("vehicle").let { it["connected"] == true && it["positionReady"] == true }
        }
        controller.startVoice()
        waitFor("voice ready") { snapshot().obj("speech")["state"] == "READY" }
        waitFor("AI available") { snapshot().obj("ai")["available"] == true }
        captured { mic.silence(30) }
    }

    private fun captured(speak: () -> Unit) {
        speak()
        waitFor("frames captured") { captures.capture.ring.framesWritten == mic.queued.get() }
    }

    private fun say(text: String) {
        engines.engine.result = listOf(alternative(text, null))
        controller.pttDown()
        captured { mic.speech(30) }
        controller.pttUp()
        captured { mic.silence(10) }
    }

    @Test fun aSpokenCommandTheRulesDoNotUnderstandIsPlannedByTheAi() {
        connect()
        ai.runtime.plans = { FakeAiRuntime.steps("take off to 3 meters", "move forward 2 meters") }
        say("take off to 3 m then step forward 2 meters")
        waitFor("AI plan shown") { snapshot()["pending"] != null }
        assertEquals("ai", snapshot().obj("pending")["source"])
        val speech = snapshot().obj("speech")
        assertEquals("take off to 3 m then step forward 2 meters", speech["heard"])
        assertNull(speech["error"])
        assertEquals("take off to 3 m then step forward 2 meters", snapshot().obj("ai")["heard"])
    }

    @Test fun aStopWhileTheAiThinksAboutASpokenCommandDiscardsItsPlan() {
        connect()
        ai.runtime.honourCancel = false
        ai.runtime.hold = CountDownLatch(1)
        ai.runtime.plans = { FakeAiRuntime.steps("take off to 3 meters", "move forward 2 meters") }
        say("take off to 3 m then step forward 2 meters")
        assertTrue(ai.runtime.started.await(5, TimeUnit.SECONDS))
        // The speech thread is free while the AI thinks: it already reported the utterance.
        waitFor("speech ready") { snapshot().obj("speech")["state"] == "READY" }
        controller.voiceStop(StopTrigger.VOICE)
        ai.runtime.hold!!.countDown()
        Thread.sleep(300)
        assertNull(snapshot()["pending"])
        assertEquals("IDLE", snapshot().obj("ai")["state"])
    }
}
