package com.aeronex.app.session

import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.audio.AudioRing
import com.aeronex.app.command.NbestMargin
import com.aeronex.app.command.Normalizer
import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.ModelLoad
import com.aeronex.app.speech.PttUtterance
import com.aeronex.app.speech.SpeechResult
import com.aeronex.app.speech.SpotterGate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Speech in the service (spec 3.3, 4.2-4.4, 4.12 triggers 4 and 5; plan T12). [start] opens a new [AudioCapture] and
 * the speech thread ("aeronex-speech"), which loads the selected language's engines once ([VoiceEngineSource], spec
 * 3.2) and then, for every captured frame:
 * - feeds the push-to-talk utterance ([PttUtterance]: 500 ms pre-roll, adaptive tail after release); at the end the
 *   final result goes through the Kotlin parser ([NbestMargin] for Vosk n-best, [PlanParser.parseText] otherwise) and
 *   a plan goes to [VoiceSink.submit] (Phone source; STOP, HOLD and status run at once, anything else waits for
 *   CONFIRM). A stop token in a streaming partial is a STOP ([StopTrigger.PARTIAL_TOKEN]).
 * - feeds the voice stop spotter ([SpotterGate]) while [VoiceSink.spotterArmed]. It also listens while PTT is held
 *   (safer reading of T12: a STOP is never held back by PTT), and its STOP discards the utterance.
 *
 * [pttDown], [pttUp] and [cancelPtt] are called on any thread (the main thread: the PTT bar, Volume-Up, a STOP key)
 * and only queue a command with the capture position at that moment; the speech thread acts on it within one frame.
 * Any STOP during PTT discards the utterance (spec 4.4). A plan from an utterance pressed before the latest STOP is
 * never submitted ([VoiceSink.stops]). Every utterance is traced under one id from [TRACE_ID_BASE] (spec 5 spans:
 * ptt_up → audio_drained → asr_final → plan_validated).
 *
 * Speech never touches the link or STOP threads: a failed microphone or model is the "No voice" level (4.11).
 *
 * Loading (0.4.0): an engine whose model loads lazily ([ModelLoad], IndicConformer) loads nothing at voice start; the
 * first press or a language selection ([setLanguage]) starts its load off this thread, and a released utterance waits
 * (DECODING, the spotter still fed) until the model is ready, at most [MODEL_WAIT_MS]. The parser is warmed once per
 * load. Engines that report [VoiceEngines.outdated] (a voice pack started or finished installing) are reopened between
 * utterances. Voice-off engines reopen at once. Voice engines reopen only when the spotter is disarmed and
 * [VoiceSink.onTheGround] is true; a disarmed spotter alone proves nothing (an RC flight in EXTERNAL mode never arms it,
 * a link loss disarms it in the air). Until the ground is known, a new pack waits for the next voice start or language
 * change. A voice pack never changes the voice STOP word in flight; only the operator's language change reloads then.
 */
class VoiceSession(
    private val openCapture: (AudioCapture.Listener) -> AudioCapture,
    private val engines: VoiceEngineSource,
    private val sink: VoiceSink,
    private val trace: Trace,
    language: String,
    private val speechThreadSetup: () -> Unit = {},
    private val saveLanguage: (String) -> Unit = {},
    private val parserWarmUp: (String) -> Unit = { PlanParser.parseText(WARM_UP_TEXT, it) },
    /** How long [stop] waits for the speech thread (shorter in tests). */
    private val joinMs: Long = JOIN_MS,
) : AutoCloseable {
    private sealed interface Command {
        data class Down(val id: Long, val at: Long?, val stops: Long) : Command
        data class Up(val id: Long, val at: Long?) : Command
        data class Cancel(val reason: String) : Command
    }

    private inner class Run(val capture: AudioCapture) {
        @Volatile var active = true
        /** The thread has run its last line ([handover] guards it). */
        var finished = false
        val thread = Thread(::loop, "aeronex-speech").apply { isDaemon = true }

        private fun loop() {
            try {
                speechThreadSetup()
            } catch (_: Exception) {
                // Default priority rather than no speech.
            }
            val ring = capture.ring
            try {
                while (active) {
                    val seen = ring.framesWritten
                    try {
                        step(ring)
                    } catch (failure: Exception) {
                        // A supervisor timeout, a malformed engine result, a JNI exception: this utterance is lost,
                        // never the process (and with it the link, heartbeat and STOP lanes; audit S3).
                        stepFailed(failure)
                    }
                    if (active) ring.awaitFrames(seen + 1, WAIT_NANOS)
                }
            } finally {
                discard("voice stopped")
                unload()
                ended(this)
            }
        }
    }

    /**
     * A run whose thread outlived [stop]'s join (a model load or a decode longer than [JOIN_MS]): no new run starts
     * until that thread has unloaded its engines, so two threads never share them (audit S1: a double close, or
     * recognizers fed after they were freed). A [start] meanwhile is remembered and made by that thread on its way out.
     * Guarded by [handover], never the session monitor, which [stop] holds while it joins.
     */
    private var ending: Run? = null
    private var startWhenEnded = false
    private val handover = Any()

    private val commands = ConcurrentLinkedQueue<Command>()
    private val ids = AtomicLong()
    @Volatile private var run: Run? = null
    /** The press in progress (main-thread view), so a late release or a second STOP finds nothing. */
    @Volatile private var held: Long? = null

    /** The selected language code ("te"). */
    @Volatile var language: String = code(language)
        private set

    /** Why voice is off before [start] (set by the host: no session, no microphone permission, refused). */
    @Volatile var offReason: VoiceReason = VoiceReason.NOT_STARTED
        set(value) {
            field = value
            sink.changed()
        }

    val running: Boolean get() = run != null

    // Speech-thread state, published through volatile fields for status().
    @Volatile private var loaded: VoiceEngines? = null
    @Volatile private var state = VoiceState.OFF
    @Volatile private var silenced = false
    @Volatile private var captureFailed = false
    @Volatile private var gateListening = false
    @Volatile private var unsure = 0
    @Volatile private var partial: String? = null
    @Volatile private var heard: String? = null
    @Volatile private var error: String? = null
    /** The operator selected the language: its next load pre-warms the model. */
    @Volatile private var prewarmNext = false
    private var utterance: PttUtterance? = null
    private var utteranceId = 0L
    private var utteranceStops = 0L
    private var gate: SpotterGate? = null
    /** No second voice STOP before this frame: one spoken "stop" is one STOP, whichever lane heard it. */
    private var quietUntil = 0L
    /** The capture frame at which the released utterance began waiting for its model. */
    private var waitingSince = NOT_WAITING

    private val listener = object : AudioCapture.Listener {
        override fun onSilencedChanged(silenced: Boolean) {
            this@VoiceSession.silenced = silenced
            sink.changed()
        }

        override fun onCaptureFailed(code: Int) {
            captureFailed = true
            trace.mark(CAPTURE_FAILED, fields = mapOf("code" to code))
            sink.changed()
        }
    }

    /** Opens the microphone and the speech thread (idempotent). A press still held is carried over. */
    @Synchronized fun start() {
        if (run != null) return
        synchronized(handover) {
            if (ending != null) {
                startWhenEnded = true // the last run's thread starts this one once it has unloaded
                return
            }
        }
        commands.clear()
        silenced = false
        captureFailed = false
        state = VoiceState.LOADING
        val capture = openCapture(listener)
        val started = Run(capture)
        held?.let { commands += Command.Down(it, null, sink.stops) }
        run = started
        capture.start()
        started.thread.start()
        sink.changed()
    }

    /** Ends the utterance, closes the engines on the speech thread and the microphone. */
    @Synchronized fun stop() {
        synchronized(handover) { startWhenEnded = false }
        val current = run ?: return
        run = null
        held = null
        current.active = false
        current.thread.join(joinMs)
        current.capture.stop()
        synchronized(handover) { if (!current.finished) ending = current }
        commands.clear()
        state = VoiceState.OFF
        partial = null
        sink.changed()
    }

    override fun close() = stop()

    /** The last line of a run's thread: a [start] that waited for it happens now. */
    private fun ended(finished: Run) {
        val restart = synchronized(handover) {
            finished.finished = true
            if (ending !== finished) return
            ending = null
            startWhenEnded.also { startWhenEnded = false }
        }
        if (restart) start()
    }

    /** A step threw: the utterance in progress is discarded and the operator told; the thread keeps running. */
    private fun stepFailed(failure: Exception) {
        trace.mark(STEP_FAILED, utteranceId, mapOf("error" to failure.javaClass.simpleName))
        try {
            discard("error")
        } catch (_: Exception) {
            utterance = null
        }
        error = ENGINE_ERROR
        if (loaded != null) state = VoiceState.READY
        sink.changed()
    }

    /** The selected language ("te-IN" or "te", already checked); the engines reload on the speech thread. */
    fun setLanguage(language: String) {
        val code = code(language)
        if (code == this.language) return
        prewarmNext = true
        this.language = code
        saveLanguage(code)
        sink.changed()
    }

    /** PTT down: the utterance starts 500 ms before this moment. Returns its trace id. */
    fun pttDown(): Long {
        val id = TRACE_ID_BASE + ids.incrementAndGet()
        val at = run?.capture?.ring?.writePosition
        trace.mark(VoiceTrace.PTT_DOWN, id, mapOf("position" to at))
        held = id
        partial = null
        heard = null
        error = null
        commands += Command.Down(id, at, sink.stops)
        sink.changed()
        return id
    }

    /** PTT up: the adaptive tail starts here. Nothing when no press is in progress (e.g. a STOP ended it). */
    fun pttUp() {
        val id = held ?: return
        held = null
        val at = run?.capture?.ring?.writePosition
        trace.mark(VoiceTrace.PTT_UP, id, mapOf("position" to at))
        commands += Command.Up(id, at)
        sink.changed()
    }

    /** A STOP (any trigger) or the app leaving: the utterance in progress, held or in its tail, is discarded. */
    fun cancelPtt(reason: String = "STOP") {
        held = null
        commands += Command.Cancel(reason)
        sink.changed()
    }

    fun status(): VoiceStatus {
        val engines = loaded
        val running = run != null
        val (level, reason) = when {
            !running -> VoiceLevel.NO_VOICE to offReason
            captureFailed -> VoiceLevel.NO_VOICE to VoiceReason.CAPTURE_FAILED
            silenced -> VoiceLevel.NO_VOICE to VoiceReason.MICROPHONE_SILENCED
            engines == null -> VoiceLevel.NO_VOICE to VoiceReason.LOADING
            engines.command == null -> VoiceLevel.NO_VOICE to (engines.reason ?: VoiceReason.ENGINE_FAILED)
            engines.command.modelLoad == ModelLoad.FAILED -> VoiceLevel.NO_VOICE to VoiceReason.ENGINE_FAILED
            unsure >= UNSURE_DEGRADES -> VoiceLevel.DEGRADED to VoiceReason.REPEATED_UNSURE
            else -> VoiceLevel.OK to null
        }
        val plan = engines?.plan
        return VoiceStatus(
            language = language,
            state = if (running) state else VoiceState.OFF,
            level = level,
            reason = reason,
            engine = engines?.command?.kind,
            spotter = engines?.spotterKind,
            nativeVoiceStop = plan?.nativeVoiceStop ?: (engines?.spotterKind == SpotterKind.VOSK_STOP_GRAMMAR),
            partialStop = plan?.partialStop ?: (engines?.command?.streaming == true),
            spotterArmed = running && gateListening,
            pttHeld = held != null,
            utterance = ids.get(),
            partial = partial,
            heard = heard,
            error = error,
        )
    }

    // --- the speech thread ---

    /** One pass: (re)load the engines, act on the queued commands, then feed every frame captured since. */
    internal fun step(ring: AudioRing) {
        if (loaded?.language != language || reopen()) load(ring)
        while (true) {
            when (val command = commands.poll() ?: break) {
                is Command.Down -> press(ring, command)
                is Command.Up -> release(ring, command)
                is Command.Cancel -> discard(command.reason)
            }
        }
        pollUtterance(ring)
        pollSpotter(ring)
        val now = loaded?.partial?.invoke().orEmpty()
        if (utterance != null && now.isNotEmpty() && now != partial) {
            if (partial.isNullOrEmpty()) trace.mark(VoiceTrace.FIRST_PARTIAL, utteranceId)
            partial = now
            sink.changed()
        }
    }

    /**
     * Outdated engines reopen between utterances. Voice-off engines at once (they only gain voice and a STOP lane);
     * voice engines only with the spotter disarmed and the vehicle known to be on the ground, since the new engines may
     * stop on other words. Away from the ground they are not even asked (working out a new plan takes time).
     */
    private fun reopen(): Boolean {
        val current = loaded ?: return false
        if (utterance != null) return false
        if (current.command != null && (sink.spotterArmed() || !sink.onTheGround())) return false
        return runCatching { current.outdated() }.getOrDefault(false)
    }

    private fun load(ring: AudioRing) {
        discard("language changed")
        unload()
        state = VoiceState.LOADING
        sink.changed()
        val prewarm = prewarmNext
        prewarmNext = false
        val code = language
        val started = System.nanoTime()
        val opened = try {
            engines.open(code)
        } catch (error: Throwable) {
            // OOM or a native failure while loading: speech degrades, the session and STOP do not (spec 4.11).
            trace.mark(VoiceTrace.ENGINES_LOADED, fields = mapOf("language" to code, "failed" to error.javaClass.simpleName))
            VoiceEngines.none(code, VoiceReason.ENGINE_FAILED)
        }
        if (prewarm) opened.command?.prewarm()
        val parserWarmMs = opened.command?.let { warmParser(code) }
        loaded = opened
        gate = opened.spotter?.let { SpotterGate(ring, it) }
        gateListening = false
        quietUntil = 0
        unsure = 0
        state = VoiceState.READY
        trace.mark(VoiceTrace.ENGINES_LOADED, fields = mapOf("language" to code, "engine" to opened.command?.kind,
            "spotter" to opened.spotterKind, "reason" to opened.reason, "ms" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
            "parserWarmMs" to parserWarmMs))
        sink.changed()
    }

    /** The first parse in a process costs 60-260 ms of class set-up (T14 bench): pay it at load, not on a command. */
    private fun warmParser(code: String): Long {
        val started = System.nanoTime()
        try {
            parserWarmUp(code)
        } catch (_: Throwable) {
            // Warm-up only: a real utterance reports any parser problem.
        }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }

    private fun unload() {
        gate = null
        gateListening = false
        val current = loaded ?: return
        loaded = null
        try {
            current.close()
        } catch (_: Throwable) {
            // Closing is best-effort; the next load opens afresh.
        }
    }

    private fun press(ring: AudioRing, command: Command.Down) {
        discard("new press")
        val engine = loaded?.command?.takeIf { it.modelLoad != ModelLoad.FAILED }
        utteranceId = command.id
        if (engine == null) {
            error = (loaded?.reason ?: VoiceReason.ENGINE_FAILED).message
            sink.changed()
            return
        }
        utteranceStops = command.stops
        waitingSince = NOT_WAITING
        // The latest moment to load a lazy model; the utterance is buffered while it loads.
        engine.prewarm()
        utterance = PttUtterance(ring, engine).also { it.press(command.at ?: 0L) }
        state = VoiceState.LISTENING
        sink.changed()
    }

    private fun release(ring: AudioRing, command: Command.Up) {
        val current = utterance ?: return
        if (command.id != utteranceId) return
        current.release(maxOf(command.at ?: ring.writePosition, 0L))
        state = VoiceState.DECODING
        sink.changed()
    }

    /** Drops the utterance in progress (the engine forgets it). */
    private fun discard(reason: String) {
        val current = utterance ?: return
        utterance = null
        current.cancel()
        trace.mark(VoiceTrace.PTT_CANCELLED, utteranceId, mapOf("reason" to reason))
        partial = null
        if (loaded != null) state = VoiceState.READY
        sink.changed()
    }

    private fun pollUtterance(ring: AudioRing) {
        val current = utterance ?: return
        when (current.poll()) {
            PttUtterance.Status.LISTENING -> {}
            PttUtterance.Status.STOP_TOKEN -> voiceStop(ring, StopTrigger.PARTIAL_TOKEN)
            PttUtterance.Status.OVERRUN -> {
                discard("overrun")
                error = OVERRUN
                sink.changed()
            }
            PttUtterance.Status.ENDED -> when (loaded?.command?.modelLoad) {
                // A lazy model still loading: the utterance waits, buffered, and the spotter keeps running.
                ModelLoad.COLD, ModelLoad.LOADING -> waitForModel(ring)
                else -> finish(current)
            }
        }
    }

    /** A plan must not turn up long after it was said: past [MODEL_WAIT_MS] the operator says it again. */
    private fun waitForModel(ring: AudioRing) {
        if (waitingSince == NOT_WAITING) waitingSince = ring.framesWritten
        if (ring.framesWritten - waitingSince < MODEL_WAIT_FRAMES) return
        discard("model still loading")
        error = MODEL_STILL_LOADING
        sink.changed()
    }

    /** The trace id of the newest press: an utterance whose id differs was replaced by it (audit S4). */
    val latestPress: Long get() = TRACE_ID_BASE + ids.get()

    private fun finish(current: PttUtterance) {
        val id = utteranceId
        utterance = null
        trace.mark(VoiceTrace.AUDIO_DRAINED, id)
        val result = try {
            current.finish()
        } catch (_: Throwable) {
            val engine = loaded?.command
            SpeechResult(engine?.kind ?: EngineKind.VOSK_GRAMMAR, language, emptyList(), 0, 0, SpeechResult.Failure.ENGINE_ERROR)
        }
        trace.mark(VoiceTrace.ASR_FINAL, id, mapOf("engine" to result.engine, "text" to result.text,
            "alternatives" to result.alternatives.size, "audioMs" to result.audioSamples / SAMPLES_PER_MS,
            "finishMs" to result.finishNanos / 1e6, "failure" to result.failure))
        if (id != latestPress) {
            // The operator pressed again while this was decoding: its plan, or its AI request, would turn up during
            // or after the new command (audit S4). The new press owns the screen.
            trace.mark(VoiceTrace.PTT_CANCELLED, id, mapOf("reason" to "new press"))
            state = VoiceState.READY
            sink.changed()
            return
        }
        heard = result.text.takeIf { it.isNotBlank() }
        partial = null
        val parsed = interpret(result)
        trace.mark(VoiceTrace.PARSED, id, mapOf("accepted" to (parsed is ParseResult.Accepted)))
        unsure = when {
            parsed is ParseResult.Rejected && parsed.message == NbestMargin.UNSURE -> unsure + 1
            parsed is ParseResult.Accepted -> 0
            else -> unsure
        }
        // The text the margin judged (the highest score), not merely the first alternative (audit S2).
        val text = judgedText(result)
        error = try {
            when (parsed) {
                // Only a transcript the rules did not understand may go to the AI path (0.4.0); it never blocks here.
                is ParseResult.Rejected ->
                    if (text.isBlank() || parsed.message == NbestMargin.UNSURE) parsed.message
                    else sink.notUnderstood(text, parsed.message, utteranceStops)
                is ParseResult.Accepted -> {
                    val answer = sink.submit(parsed.plan, text, id, utteranceStops)
                    if (answer["error"] == null) trace.mark(TracePoint.PLAN_VALIDATED, id) else trace.mark(VoiceTrace.PLAN_REJECTED, id)
                    answer["error"] as String?
                }
            }
        } catch (failure: Exception) {
            // The supervisor did not answer in time, or refused the call: shown, never thrown (audit S3).
            trace.mark(STEP_FAILED, id, mapOf("error" to failure.javaClass.simpleName))
            failure.message ?: ENGINE_ERROR
        }
        state = VoiceState.READY
        sink.changed()
    }

    private fun pollSpotter(ring: AudioRing) {
        val armed = sink.spotterArmed()
        val current = gate ?: return
        if (!armed) {
            if (gateListening) {
                gateListening = false
                sink.changed()
            }
            return
        }
        if (!gateListening) {
            current.start()
            gateListening = true
            sink.changed()
        }
        // The gate itself skips the rest of a segment after a detection.
        if (current.poll() && ring.framesWritten >= quietUntil) voiceStop(ring, StopTrigger.VOICE)
    }

    /** A spoken STOP: PTT ends and its utterance is discarded, then the supervisor stops (it may send nothing). */
    private fun voiceStop(ring: AudioRing, trigger: StopTrigger) {
        held = null
        discard(trigger.name)
        quietUntil = ring.framesWritten + REFRACTORY_FRAMES
        sink.voiceStop(trigger)
    }

    companion object {
        /** Voice trace ids, far above the supervisor's plan and STOP ids, so spans never pair across them. */
        const val TRACE_ID_BASE = 1L shl 48
        /** Spec 4.11: this many "not clear" answers in a row is Voice degraded. */
        const val UNSURE_DEGRADES = 3
        /** 1 s: the spotter may still be hearing the word the PTT recognizer just stopped on. */
        private const val REFRACTORY_FRAMES = 1_000L / AudioRing.FRAME_MS
        private const val NOT_WAITING = -1L
        const val CAPTURE_FAILED = "capture_failed"
        const val STEP_FAILED = "speech_step_failed"
        const val OVERRUN = "Some of the audio was lost. Say the command again."
        const val NO_MODEL = "No speech model is loaded. Use the tap pad or typed commands."
        const val TOO_LONG = "The command was too long. Say it in under 10 seconds, or split it."
        const val ENGINE_ERROR = "Speech recognition failed. Say it again or use the tap pad."
        const val MODEL_STILL_LOADING = "The voice model is still loading. Say the command again in a moment."
        /** How long a released utterance waits for a lazy model (in captured audio): no plan long after it was said. */
        const val MODEL_WAIT_MS = 10_000L
        private const val MODEL_WAIT_FRAMES = MODEL_WAIT_MS / AudioRing.FRAME_MS
        private val WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20)
        private const val WARM_UP_TEXT = "hover"
        private const val JOIN_MS = 3_000L
        private const val SAMPLES_PER_MS = AudioRing.SAMPLE_RATE / 1000

        private fun code(language: String) = Normalizer.languageCode(language) ?: language

        /** The alternative [interpret] judged: the highest Vosk score, else the first. */
        internal fun judgedText(result: SpeechResult): String {
            val alternatives = result.alternatives
            if (result.engine != EngineKind.VOSK_GRAMMAR || alternatives.isEmpty() || alternatives.any { it.score == null }) {
                return result.text
            }
            return alternatives.maxByOrNull { it.score!! }!!.text
        }

        /** The final result → a plan or the reason there is none (no AI, spec 4.9). */
        fun interpret(result: SpeechResult): ParseResult {
            when (result.failure) {
                SpeechResult.Failure.NO_MODEL -> return ParseResult.Rejected(NO_MODEL)
                SpeechResult.Failure.TOO_LONG -> return ParseResult.Rejected(TOO_LONG)
                SpeechResult.Failure.ENGINE_ERROR -> return ParseResult.Rejected(ENGINE_ERROR)
                null -> {}
            }
            val alternatives = result.alternatives
            if (alternatives.isEmpty()) return ParseResult.Rejected(NbestMargin.NOTHING_HEARD)
            val scores = alternatives.map { it.score }
            return if (result.engine == EngineKind.VOSK_GRAMMAR && scores.all { it != null }) {
                NbestMargin.decide(alternatives.map { NbestMargin.Alternative(it.text, it.score!!) }, result.language)
            } else if (alternatives.first().text.isBlank()) {
                ParseResult.Rejected(NbestMargin.NOTHING_HEARD)
            } else {
                PlanParser.parseText(alternatives.first().text, result.language)
            }
        }
    }
}
