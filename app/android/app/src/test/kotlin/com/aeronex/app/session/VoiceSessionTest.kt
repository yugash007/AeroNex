package com.aeronex.app.session

import com.aeronex.app.audio.AudioRing
import com.aeronex.app.command.NbestMargin
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.speech.AvailableEngines
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.ModelLoad
import com.aeronex.app.speech.SpeechResult
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The speech thread: PTT → final transcript → parser → supervisor (a [VoiceSink] here), and the voice STOP lanes. */
class VoiceSessionTest {
    private class Sink : VoiceSink {
        @Volatile override var stops = 0L
        @Volatile var armed = false
        @Volatile var ground = false
        val plans = CopyOnWriteArrayList<Pair<String, String>>()
        val stopped = CopyOnWriteArrayList<StopTrigger>()
        @Volatile var answer: Map<String, Any?> = mapOf("token" to "t-1", "expiresInMs" to 15_000L, "summary" to "")
        /** submit() throws this, when set (the supervisor did not answer). */
        @Volatile var failure: Exception? = null

        override fun submit(plan: FlightPlan, transcript: String, traceId: Long, stopsAtPress: Long): Map<String, Any?> {
            failure?.let { throw it }
            if (stops != stopsAtPress) return mapOf("error" to "stopped")
            plans += plan.toString() to transcript
            return answer
        }

        override fun voiceStop(trigger: StopTrigger) {
            stops++
            stopped += trigger
        }

        override fun spotterArmed() = armed
        override fun onTheGround() = ground
        override fun changed() {}
    }

    private val sink = Sink()
    private val trace = Trace()
    private val engines = ScriptedEngines()
    private val captures = QueueCaptures()
    private val saved = CopyOnWriteArrayList<String>()
    private val warmed = CopyOnWriteArrayList<Pair<String, String>>()
    private val voice = VoiceSession(captures, engines, sink, trace, "en", saveLanguage = { saved += it },
        parserWarmUp = { warmed += it to Thread.currentThread().name })

    @After fun tearDown() = voice.stop()

    private val ring: AudioRing get() = captures.capture.ring
    private val mic: QueuePcm get() = captures.source

    /** Starts capture and the speech thread with [result] as the recognizer's answer; 1 s of background first. */
    private fun start(vararg result: com.aeronex.app.speech.SpeechAlternative) {
        engines.nextResult = result.toList()
        voice.start()
        waitFor("engines loaded") { voice.status().state == VoiceState.READY }
        captured { mic.silence(50) }
    }

    /** Runs [speak] and waits until the capture thread put every queued frame in the ring. */
    private fun captured(speak: () -> Unit) {
        speak()
        waitFor("frames captured") { ring.framesWritten == mic.queued.get() }
    }

    /**
     * The message the speech thread publishes for a decode. The engine's finish count rises before the parse, and the
     * first parse in a process can take about 100 ms, so wait for it (pttDown clears the previous one).
     */
    private fun outcome(): String? {
        waitFor("decode outcome published") { voice.status().error != null }
        return voice.status().error
    }

    private fun say(speechFrames: Int = 40) {
        voice.pttDown()
        captured { mic.speech(speechFrames) }
        voice.pttUp()
        captured { mic.silence(10) }
    }

    @Test fun aReleasedUtteranceIsParsedAndSubmittedOnceWithEverySpanTraced() {
        start(alternative("take off to 3 m"))
        say()
        waitFor("submitted") { sink.plans.isNotEmpty() }
        assertEquals(listOf("1. Take off to 3 m" to "take off to 3 m"), sink.plans.toList())
        assertTrue("500 ms pre-roll + 800 ms + the tail", engines.engine.heard.get() >= AudioRing.samplesForMs(1_300))
        val status = voice.status()
        assertEquals("take off to 3 m", status.heard)
        assertNull(status.error)
        assertFalse(status.pttHeld)
        assertEquals(VoiceLevel.OK, status.level)
        val points = trace.events().filter { it.id >= VoiceSession.TRACE_ID_BASE }
        assertEquals(listOf(VoiceTrace.PTT_DOWN, VoiceTrace.PTT_UP, VoiceTrace.AUDIO_DRAINED, VoiceTrace.ASR_FINAL,
            VoiceTrace.PARSED, TracePoint.PLAN_VALIDATED), points.map { it.name })
        assertEquals("one utterance, one id", 1, points.map { it.id }.distinct().size)
        say()
        waitFor("second utterance") { sink.plans.size == 2 }
        assertEquals(2, engines.engine.finishes.get())
    }

    @Test fun aStopTokenInAPartialStopsAndDiscardsTheUtterance() {
        start(alternative("stop"))
        engines.engine.stopTokenAfter = AudioRing.samplesForMs(700).toLong()
        voice.pttDown()
        captured { mic.speech(30) }
        waitFor("partial-token STOP") { sink.stopped.isNotEmpty() }
        assertEquals(listOf(StopTrigger.PARTIAL_TOKEN), sink.stopped.toList())
        assertEquals(1, engines.engine.cancels.get())
        voice.pttUp()
        captured { mic.silence(20) }
        assertEquals(0, engines.engine.finishes.get())
        assertTrue(sink.plans.isEmpty())
        assertFalse(voice.status().pttHeld)
    }

    @Test fun theSpotterStopsEvenWhilePttIsHeldAndTheUtteranceIsDiscarded() {
        start(alternative("take off to 3 m"))
        sink.armed = true
        // The gate listens from when the speech thread sees it armed; speech captured before then is not its.
        waitFor("spotter armed") { voice.status().spotterArmed }
        voice.pttDown()
        captured { mic.speech(30) }
        waitFor("voice STOP") { sink.stopped.isNotEmpty() }
        assertEquals(listOf(StopTrigger.VOICE), sink.stopped.toList())
        assertEquals("STOP ends PTT", 1, engines.engine.cancels.get())
        voice.pttUp()
        captured { mic.silence(20) }
        assertEquals(0, engines.engine.finishes.get())
        assertTrue(sink.plans.isEmpty())
    }

    @Test fun theSpotterHearsNothingUntilTheVehicleIsArmedWithTheAppOwningTheMode() {
        start()
        captured { mic.speech(30) }
        captured { mic.silence(40) }
        assertEquals(0L, engines.spotter.fed.get())
        assertFalse(voice.status().spotterArmed)
        sink.armed = true
        captured { mic.silence(5) }
        assertTrue(voice.status().spotterArmed)
        captured { mic.speech(30) }
        waitFor("voice STOP") { sink.stopped.toList() == listOf(StopTrigger.VOICE) }
        assertTrue("only audio from after arming", engines.spotter.fed.get() <= AudioRing.samplesForMs(900))
    }

    @Test fun cancellingDuringPttDiscardsTheUtteranceAndTheLateReleaseDoesNothing() {
        start(alternative("take off to 3 m"))
        voice.pttDown()
        captured { mic.speech(20) }
        voice.cancelPtt()
        waitFor("cancelled") { engines.engine.cancels.get() == 1 }
        voice.pttUp()
        captured { mic.silence(20) }
        assertEquals(0, engines.engine.finishes.get())
        assertTrue(sink.plans.isEmpty())
        assertTrue(trace.events().any { it.name == VoiceTrace.PTT_CANCELLED })
    }

    @Test fun aPlanFromAnUtterancePressedBeforeTheLatestStopIsNeverSubmitted() {
        start(alternative("take off to 3 m"))
        voice.pttDown()
        captured { mic.speech(20) }
        voice.pttUp()
        sink.stops++ // a STOP elsewhere before the tail ended
        captured { mic.silence(20) }
        waitFor("decoded") { engines.engine.finishes.get() == 1 || engines.engine.cancels.get() == 1 }
        captured { mic.silence(5) }
        assertTrue(sink.plans.isEmpty())
    }

    @Test fun rejectionsAreShownAndRepeatedUnclearAnswersDegradeVoice() {
        start(alternative("move forward 2 m", 101.0), alternative("move forward 9 m", 100.0))
        repeat(3) { n ->
            say()
            waitFor("decoded ${n + 1}") { engines.engine.finishes.get() == n + 1 }
            assertEquals(NbestMargin.UNSURE, outcome())
        }
        assertEquals(VoiceLevel.DEGRADED, voice.status().level)
        assertEquals(VoiceReason.REPEATED_UNSURE, voice.status().reason)
        engines.engine.result = listOf(alternative("take off to 3 m"))
        say()
        waitFor("submitted") { sink.plans.isNotEmpty() }
        assertEquals(VoiceLevel.OK, voice.status().level)
        engines.engine.result = emptyList()
        say()
        waitFor("decoded") { engines.engine.finishes.get() == 5 }
        assertEquals(NbestMargin.NOTHING_HEARD, outcome())
        engines.engine.failure = SpeechResult.Failure.TOO_LONG
        say()
        waitFor("decoded") { engines.engine.finishes.get() == 6 }
        assertEquals(VoiceSession.TOO_LONG, outcome())
    }

    @Test fun ctcTranscriptsGoThroughTheTextParser() {
        engines.kind = EngineKind.SHERPA_CTC
        voice.setLanguage("te-IN")
        start(alternative("3 మీటర్లు టేకాఫ్", null))
        say()
        waitFor("submitted") { sink.plans.isNotEmpty() }
        assertEquals("1. Take off to 3 m", sink.plans.single().first)
        assertEquals(EngineKind.SHERPA_CTC, voice.status().engine)
    }

    @Test fun aLanguageWithoutVoiceOnThisPhoneSaysSoAndThePressDoesNothing() {
        val tierL = EnginePolicy(DeviceFacts(emptyList(), 2L shl 30, lowRamDevice = false, cpuCores = 4))
        val plan = tierL.plan("ta", AvailableEngines(emptySet(), emptyList()))
        assertEquals(VoiceReason.NEEDS_NEWER_PHONE, VoiceEngines.reasonFor(plan))
        assertEquals("Voice for this language needs a newer phone — use the tap pad", VoiceReason.NEEDS_NEWER_PHONE.message)
        val none = VoiceSession(captures, ScriptedEngines(reasons = mapOf("ta" to VoiceReason.NEEDS_NEWER_PHONE)), sink,
            trace, "ta")
        try {
            none.start()
            waitFor("loaded") { none.status().state == VoiceState.READY }
            val status = none.status()
            assertEquals(VoiceLevel.NO_VOICE, status.level)
            assertEquals(VoiceReason.NEEDS_NEWER_PHONE, status.reason)
            none.pttDown()
            captured { mic.speech(10) }
            none.pttUp()
            captured { mic.silence(10) }
            assertEquals(VoiceReason.NEEDS_NEWER_PHONE.message, none.status().error)
            assertTrue(sink.plans.isEmpty())
        } finally {
            none.stop()
        }
    }

    @Test fun aLanguageChangeReloadsOnTheSpeechThreadAndIsSaved() {
        start()
        val first = engines.engine
        voice.setLanguage("hi-IN")
        waitFor("reloaded") { engines.engines.size == 2 && voice.status().state == VoiceState.READY }
        assertEquals("hi", engines.engine.language)
        assertEquals("hi", voice.status().language)
        assertEquals(listOf("hi"), saved.toList())
        assertEquals("closed on the thread that opened it", engines.openedOn, first.closedOn)
        voice.stop()
        assertEquals(engines.openedOn, engines.engine.closedOn)
        assertTrue(engines.spotter.closed)
        assertEquals(VoiceState.OFF, voice.status().state)
        assertFalse(captures.capture.running)
    }

    @Test fun silencedCaptureMeansNoVoice() {
        start()
        mic.clientSilenced!!(true)
        captured { mic.silence(2) }
        assertEquals(VoiceLevel.NO_VOICE, voice.status().level)
        assertEquals(VoiceReason.MICROPHONE_SILENCED, voice.status().reason)
        mic.clientSilenced!!(false)
        captured { mic.silence(2) }
        assertEquals(VoiceLevel.OK, voice.status().level)
    }

    // --- 0.4.0 voice loading: lazy CTC model, pre-warm, pack installs, parser warm-up ---

    @Test fun aLazyModelLoadsAtTheFirstPressAndTheUtteranceWaitsForItWithoutBlockingTheSpeechThread() {
        engines.model = ModelLoad.COLD
        start(alternative("take off to 3 m", null))
        assertEquals("voice start loads no model", 0, engines.engine.prewarms.get())
        assertEquals(VoiceLevel.OK, voice.status().level)
        say()
        assertTrue("the first press starts the load", engines.engine.prewarms.get() >= 1)
        waitFor("released, waiting for the model") { voice.status().state == VoiceState.DECODING }
        sink.armed = true
        waitFor("spotter armed") { voice.status().spotterArmed }
        captured { mic.speech(30) }
        waitFor("the spotter still runs meanwhile") { sink.stopped.toList() == listOf(StopTrigger.VOICE) }
        assertEquals("the STOP discards the waiting utterance", 1, engines.engine.cancels.get())
        engines.engine.modelState = ModelLoad.READY
        captured { mic.silence(10) }
        assertEquals(0, engines.engine.finishes.get())
        assertTrue(sink.plans.isEmpty())
    }

    @Test fun theWaitingUtteranceDecodesOnceTheModelIsReady() {
        engines.model = ModelLoad.COLD
        start(alternative("take off to 3 m", null))
        say()
        waitFor("waiting for the model") { voice.status().state == VoiceState.DECODING }
        captured { mic.silence(10) }
        assertEquals(0, engines.engine.finishes.get())
        engines.engine.modelState = ModelLoad.READY
        captured { mic.silence(2) }
        waitFor("submitted") { sink.plans.isNotEmpty() }
        assertEquals(1, engines.engine.finishes.get())
        assertEquals(VoiceState.READY, voice.status().state)
    }

    @Test fun selectingALanguagePrewarmsItsModelButVoiceStartDoesNot() {
        engines.model = ModelLoad.COLD
        start()
        assertEquals(0, engines.engine.prewarms.get())
        voice.setLanguage("te")
        waitFor("te opened") { engines.engines.size == 2 && voice.status().state == VoiceState.READY }
        assertEquals("te", engines.engine.language)
        waitFor("pre-warmed") { engines.engine.prewarms.get() == 1 }
        assertEquals(0, engines.engines.first().prewarms.get())
    }

    @Test fun aModelThatFailedToLoadIsNoVoice() {
        engines.model = ModelLoad.FAILED
        start()
        assertEquals(VoiceLevel.NO_VOICE, voice.status().level)
        assertEquals(VoiceReason.ENGINE_FAILED, voice.status().reason)
    }

    @Test fun anUtteranceWaitingTooLongForTheModelIsDroppedWithAMessage() {
        engines.model = ModelLoad.COLD
        start(alternative("take off to 3 m", null))
        say()
        waitFor("waiting for the model") { voice.status().state == VoiceState.DECODING }
        captured { mic.silence((VoiceSession.MODEL_WAIT_MS / AudioRing.FRAME_MS).toInt() + 5) }
        assertEquals("no plan long after it was spoken", VoiceSession.MODEL_STILL_LOADING, outcome())
        assertEquals(VoiceState.READY, voice.status().state)
        assertEquals(1, engines.engine.cancels.get())
        engines.engine.modelState = ModelLoad.READY
        captured { mic.silence(5) }
        assertEquals(0, engines.engine.finishes.get())
        assertTrue(sink.plans.isEmpty())
    }

    /** A finished pack install reopens the voice engines once, between utterances, while the vehicle is on the ground. */
    @Test fun outdatedEnginesReopenOnTheGround() {
        sink.ground = true
        start()
        engines.markOutdated()
        captured { mic.silence(2) }
        waitFor("reopened") { engines.opened.size == 2 && voice.status().state == VoiceState.READY }
        captured { mic.silence(10) }
        assertEquals("once", 2, engines.opened.size)
    }

    /**
     * A spotter that never armed is no proof of the ground: the pilot may fly with RC (EXTERNAL mode, so the spotter
     * never arms) or the link may be down since voice started. Voice engines wait until the ground is known.
     */
    @Test fun outdatedVoiceEnginesWaitForTheGroundEvenWhenTheSpotterNeverArmed() {
        start()
        engines.markOutdated()
        captured { mic.silence(20) }
        assertFalse(voice.status().spotterArmed)
        assertEquals("not without the ground", 1, engines.opened.size)
        sink.ground = true
        captured { mic.silence(2) }
        waitFor("reopened once on the ground") { engines.opened.size == 2 && voice.status().state == VoiceState.READY }
    }

    /** Away from the ground the session does not even ask whether voice engines are outdated (that can work out a plan). */
    @Test fun voiceEnginesAreAskedWhetherTheyAreOutdatedOnlyOnTheGround() {
        start()
        sink.armed = true
        waitFor("spotter armed") { voice.status().spotterArmed }
        val asked = engines.outdatedPolls.get()
        captured { mic.silence(20) }
        assertEquals("not while the spotter listens", asked, engines.outdatedPolls.get())
        sink.armed = false
        captured { mic.silence(20) }
        assertEquals("not with the ground unknown", asked, engines.outdatedPolls.get())
        sink.ground = true
        captured { mic.silence(2) }
        waitFor("asked on the ground") { engines.outdatedPolls.get() > asked }
    }

    /**
     * The voice STOP word never changes in flight: once the spotter listened, a link loss or an RC takeover (both disarm
     * the spotter in the air) is no time to swap engines; they wait until the vehicle is known to be on the ground.
     */
    @Test fun afterAFlightOutdatedVoiceEnginesWaitUntilTheVehicleIsKnownToBeOnTheGround() {
        start()
        sink.armed = true
        waitFor("spotter armed") { voice.status().spotterArmed }
        engines.markOutdated()
        captured { mic.silence(10) }
        assertEquals("never while the spotter listens", 1, engines.opened.size)
        sink.armed = false
        captured { mic.silence(20) }
        assertFalse(voice.status().spotterArmed)
        assertEquals("not on a disarmed spotter alone", 1, engines.opened.size)
        sink.ground = true
        captured { mic.silence(2) }
        waitFor("reopened once on the ground") { engines.opened.size == 2 && voice.status().state == VoiceState.READY }
    }

    /** The operator's language change reloads in flight too; a pack that finishes afterwards still waits for the ground. */
    @Test fun aLanguageChangeReloadsInFlightButAPackStillWaitsForTheGround() {
        start()
        sink.armed = true
        waitFor("spotter armed") { voice.status().spotterArmed }
        sink.armed = false
        voice.setLanguage("hi")
        waitFor("reloaded") { engines.opened.size == 2 && voice.status().state == VoiceState.READY }
        assertEquals("hi", engines.engine.language)
        engines.markOutdated()
        captured { mic.silence(20) }
        assertEquals("no pack swap while the link is lost", 2, engines.opened.size)
    }

    @Test fun outdatedEnginesNeverReopenDuringAnUtterance() {
        sink.ground = true
        start(alternative("take off to 3 m"))
        voice.pttDown()
        captured { mic.speech(10) }
        engines.markOutdated()
        captured { mic.speech(10) }
        assertEquals(1, engines.opened.size)
        voice.pttUp()
        captured { mic.silence(10) }
        waitFor("submitted") { sink.plans.isNotEmpty() }
        waitFor("reopened after the utterance") { engines.opened.size == 2 }
    }

    /** T15 follow-up: voice waiting for a pack says "Installing the voice pack", and comes up when it is in, even armed. */
    @Test fun waitingForAVoicePackSaysSoAndVoiceComesUpWhenItArrives() {
        val installing = ScriptedEngines(reasons = mapOf("ta" to VoiceReason.INSTALLING_PACK))
        val waiting = VoiceSession(captures, installing, sink, trace, "ta")
        try {
            waiting.start()
            waitFor("loaded") { waiting.status().state == VoiceState.READY }
            assertEquals(VoiceLevel.NO_VOICE, waiting.status().level)
            assertEquals(VoiceReason.INSTALLING_PACK, waiting.status().reason)
            assertEquals("Installing the voice pack…", VoiceReason.INSTALLING_PACK.message)
            sink.armed = true
            installing.reasons.clear()
            installing.markOutdated()
            captured { mic.silence(2) }
            waitFor("voice up") { waiting.status().level == VoiceLevel.OK }
            assertEquals(2, installing.opened.size)
            // Voice engines now: a disarmed spotter (a link loss in flight) is still no time to swap them.
            sink.armed = false
            installing.markOutdated()
            captured { mic.silence(20) }
            assertEquals("voice engines wait for the ground", 2, installing.opened.size)
        } finally {
            waiting.stop()
        }
    }

    @Test fun theParserIsWarmedOnTheSpeechThreadWhenTheEnginesLoad() {
        start()
        assertEquals("before any press", listOf("en" to "aeronex-speech"), warmed.toList())
        val loaded = trace.events().last { it.name == VoiceTrace.ENGINES_LOADED }
        assertTrue(loaded.fields.toString(), (loaded.fields["parserWarmMs"] as Long) >= 0)
        voice.setLanguage("hi")
        waitFor("reloaded") { engines.engines.size == 2 && voice.status().state == VoiceState.READY }
        assertEquals(listOf("en", "hi"), warmed.map { it.first })
    }

    @Test fun beforeStartVoiceIsOffWithTheReasonTheHostGave() {
        assertEquals(VoiceState.OFF, voice.status().state)
        assertEquals(VoiceReason.NOT_STARTED, voice.status().reason)
        voice.offReason = VoiceReason.NO_MICROPHONE
        assertEquals(VoiceReason.NO_MICROPHONE, voice.status().reason)
        assertEquals("en", voice.status().language)
    }

    /** Audit S3: a supervisor that does not answer is an error on screen; the speech thread lives on. */
    @Test fun aSupervisorFailureIsShownAndTheSpeechThreadLivesOn() {
        start(alternative("take off to 3 m"))
        sink.failure = SessionException(SessionController.INTERNAL, "The flight supervisor did not answer.")
        say()
        assertEquals("The flight supervisor did not answer.", outcome())
        sink.failure = null
        say()
        waitFor("the next utterance still works") { sink.plans.isNotEmpty() }
    }

    /** Audit S3: an engine failure mid-utterance discards it; the next one works. */
    @Test fun anEngineFailureMidUtteranceDiscardsItAndTheNextWorks() {
        start(alternative("take off to 3 m"))
        engines.engine.acceptFailure = IllegalStateException("native")
        say()
        assertEquals(VoiceSession.ENGINE_ERROR, outcome())
        engines.engine.acceptFailure = null
        say()
        waitFor("the next utterance still works") { sink.plans.isNotEmpty() }
    }

    /** Audit S4: a press made while the last utterance decodes replaces it; the old one is never submitted. */
    @Test fun anUtteranceReplacedByANewerPressIsNeverSubmitted() {
        start(alternative("take off to 3 m"))
        val decode = java.util.concurrent.CountDownLatch(1)
        engines.engine.finishGate = decode
        say()
        waitFor("decoding") { voice.status().state == VoiceState.DECODING }
        voice.pttDown() // the operator speaks again while the first one decodes
        decode.countDown()
        engines.engine.finishGate = null
        captured { mic.speech(40) }
        voice.pttUp()
        captured { mic.silence(10) }
        waitFor("the second utterance") { sink.plans.isNotEmpty() }
        assertEquals("only the newer press", 1, sink.plans.size)
        assertEquals(2, engines.engine.finishes.get())
    }
}

class VoiceSessionRestartTest {
    private class QuietSink : VoiceSink {
        override val stops = 0L
        override fun submit(plan: FlightPlan, transcript: String, traceId: Long, stopsAtPress: Long) = emptyMap<String, Any?>()
        override fun voiceStop(trigger: StopTrigger) {}
        override fun spotterArmed() = false
        override fun onTheGround() = true
        override fun changed() {}
    }

    /**
     * Audit S1: a stop whose join gave up (a model load slower than it) and a start right after never run two speech
     * threads over the same engines: the new one starts only once the old one has closed what it opened.
     */
    @Test fun aStartAfterAStopThatOutwaitedItsJoinWaitsForTheOldThread() {
        val engines = ScriptedEngines()
        val voice = VoiceSession(QueueCaptures(), engines, QuietSink(), Trace(), "en", joinMs = 100)
        val load = java.util.concurrent.CountDownLatch(1)
        engines.openGate = load
        voice.start()
        waitFor("loading") { engines.opening.get() == 1 }
        voice.stop() // gives up after 100 ms: the thread is still inside open()
        voice.start() // must wait for that thread
        assertFalse(voice.running)
        Thread.sleep(200)
        assertEquals("no second thread meanwhile", 1, engines.mostOpening.get())
        engines.openGate = null
        load.countDown()
        waitFor("the deferred start ran") { voice.running && voice.status().state == VoiceState.READY }
        assertEquals("never two loads at once", 1, engines.mostOpening.get())
        assertEquals(2, engines.opened.size)
        assertTrue("the old thread closed what it opened", engines.engines.first().closedOn != null)
        voice.stop()
    }

    /** A stop cancels a start that was waiting for the old thread. */
    @Test fun aStopCancelsAWaitingStart() {
        val engines = ScriptedEngines()
        val voice = VoiceSession(QueueCaptures(), engines, QuietSink(), Trace(), "en", joinMs = 50)
        val load = java.util.concurrent.CountDownLatch(1)
        engines.openGate = load
        voice.start()
        waitFor("loading") { engines.opening.get() == 1 }
        voice.stop()
        voice.start()
        voice.stop()
        load.countDown()
        Thread.sleep(300)
        assertFalse(voice.running)
        assertEquals(1, engines.opened.size)
    }

}
