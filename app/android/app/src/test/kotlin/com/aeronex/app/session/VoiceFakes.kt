package com.aeronex.app.session

import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.audio.AudioSignals
import com.aeronex.app.audio.PcmSource
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.ModelLoad
import com.aeronex.app.speech.SpeechAlternative
import com.aeronex.app.speech.SpeechEngine
import com.aeronex.app.speech.SpeechResult
import com.aeronex.app.speech.StopSpotter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A microphone the test speaks into: [silence] and [speech] queue 20 ms frames; [read] blocks until one is queued. */
internal class QueuePcm : PcmSource {
    private val frames = LinkedBlockingQueue<ShortArray>()
    private var current: ShortArray? = null
    private var used = 0
    private var seed = 0L
    private var phase = 0L
    @Volatile private var stopped = false
    @Volatile var clientSilenced: ((Boolean) -> Unit)? = null
    val queued = AtomicLong()

    override fun start(onClientSilenced: (Boolean) -> Unit) {
        clientSilenced = onClientSilenced
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        while (!stopped) {
            val frame = current ?: frames.poll(10, TimeUnit.MILLISECONDS) ?: continue
            current = frame
            val n = minOf(count, frame.size - used)
            System.arraycopy(frame, used, buffer, offset, n)
            used += n
            if (used == frame.size) {
                current = null
                used = 0
            }
            return n
        }
        return 0
    }

    override fun stop() {
        stopped = true
    }

    override fun release() {}

    /** Background noise at -60 dBFS: never speech for the VAD. */
    fun silence(frames: Int) = repeat(frames) { push(AudioSignals.noise(-60.0, seed++)) }

    /** A -20 dBFS tone: speech for the VAD. */
    fun speech(frames: Int) = repeat(frames) { push(AudioSignals.tone(-20.0, phase++)) }

    private fun push(frame: ShortArray) {
        queued.incrementAndGet()
        frames.put(frame)
    }
}

/**
 * A command recognizer that returns [result] and can report a stop token in its partials after some audio. [modelState]
 * plays a lazily loaded model ([com.aeronex.app.speech.LazyCtcEngine]): [prewarm] moves COLD to LOADING and
 * the test sets READY or FAILED.
 */
internal class ScriptedEngine(
    override val language: String = "en",
    override val kind: EngineKind = EngineKind.VOSK_GRAMMAR,
    @Volatile var modelState: ModelLoad = ModelLoad.READY,
) : SpeechEngine {
    @Volatile var result: List<SpeechAlternative> = emptyList()
    @Volatile var failure: SpeechResult.Failure? = null
    /** accept() reports a stop token once this many samples of one utterance were heard. */
    @Volatile var stopTokenAfter = Long.MAX_VALUE
    val heard = AtomicLong()
    val begins = AtomicInteger()
    val cancels = AtomicInteger()
    val finishes = AtomicInteger()
    @Volatile var closedOn: String? = null
    val prewarms = AtomicInteger()
    /** finish() waits for this, when set (a slow decode). */
    @Volatile var finishGate: java.util.concurrent.CountDownLatch? = null
    /** accept() throws this, when set (a native failure mid-utterance). */
    @Volatile var acceptFailure: RuntimeException? = null
    private var fired = false

    override val modelLoad: ModelLoad get() = modelState

    override fun prewarm() {
        prewarms.incrementAndGet()
        if (modelState == ModelLoad.COLD) modelState = ModelLoad.LOADING
    }

    override fun begin() {
        begins.incrementAndGet()
        heard.set(0)
        fired = false
    }

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        acceptFailure?.let { throw it }
        val total = heard.addAndGet(count.toLong())
        if (fired || total < stopTokenAfter) return false
        fired = true
        return true
    }

    override fun finish(): SpeechResult {
        finishGate?.await()
        finishes.incrementAndGet()
        return SpeechResult(kind, language, result, heard.get(), 2_000_000, failure)
    }

    override fun cancel() {
        cancels.incrementAndGet()
    }

    override fun close() {
        closedOn = Thread.currentThread().name
    }
}

/** A stop spotter that detects "stop" once [fireAfter] samples of one VAD segment were fed (when [enabled]). */
internal class ScriptedSpotter(@Volatile var fireAfter: Long = 4_800) : StopSpotter {
    val fed = AtomicLong()
    private var segment = 0L
    @Volatile var enabled = true
    @Volatile var closed = false

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        fed.addAndGet(count.toLong())
        segment += count
        if (!enabled || segment < fireAfter) return false
        segment = 0
        return true
    }

    override fun reset() {
        segment = 0
    }

    override fun close() {
        closed = true
    }
}

/**
 * Opens a [ScriptedEngine] (and optionally a [ScriptedSpotter]) per language and records what it opened. [reasons] can
 * change while the session runs; [markOutdated] makes the engines opened last say a reopen would give more, and
 * [outdatedPolls] counts how often the session asked.
 */
internal class ScriptedEngines(
    private val withSpotter: Boolean = true,
    reasons: Map<String, VoiceReason> = emptyMap(),
) : VoiceEngineSource {
    val reasons = ConcurrentHashMap(reasons)
    private val outdated = CopyOnWriteArrayList<AtomicBoolean>()
    val outdatedPolls = AtomicInteger()
    val opened = CopyOnWriteArrayList<VoiceEngines>()
    val engines = CopyOnWriteArrayList<ScriptedEngine>()
    val spotters = CopyOnWriteArrayList<ScriptedSpotter>()
    @Volatile var nextResult: List<SpeechAlternative> = emptyList()
    @Volatile var kind = EngineKind.VOSK_GRAMMAR
    /** The model state of the engines opened from now on. */
    @Volatile var model = ModelLoad.READY
    @Volatile var openedOn: String? = null
    /** open() waits for this, when set (a model load slower than VoiceSession.stop's join). */
    @Volatile var openGate: java.util.concurrent.CountDownLatch? = null
    /** Speech threads inside open() right now, and the most there ever were. */
    val opening = AtomicInteger()
    val mostOpening = AtomicInteger()

    val engine: ScriptedEngine get() = engines.last()
    val spotter: ScriptedSpotter get() = spotters.last()

    fun markOutdated() = outdated.last().set(true)

    override fun open(language: String): VoiceEngines {
        openedOn = Thread.currentThread().name
        mostOpening.accumulateAndGet(opening.incrementAndGet(), ::maxOf)
        try {
            openGate?.await()
        } finally {
            opening.decrementAndGet()
        }
        val stale = AtomicBoolean().also { outdated += it }
        val asked = { outdatedPolls.incrementAndGet(); stale.get() }
        reasons[language]?.let { return VoiceEngines.none(language, it, outdated = asked).also { opened += it } }
        val engine = ScriptedEngine(language, kind, model).also { it.result = nextResult; engines += it }
        val spotter = if (withSpotter) ScriptedSpotter().also { spotters += it } else null
        return VoiceEngines(language, engine, spotter, partial = { "" }, outdated = asked).also { opened += it }
    }
}

/** The capture factory a [VoiceSession] uses, over [pcm]; [captures] keeps every capture it built. */
internal class QueueCaptures(val pcm: () -> QueuePcm = { QueuePcm() }) : (AudioCapture.Listener) -> AudioCapture {
    val sources = CopyOnWriteArrayList<QueuePcm>()
    val captures = CopyOnWriteArrayList<AudioCapture>()
    val source: QueuePcm get() = sources.last()
    val capture: AudioCapture get() = captures.last()

    override fun invoke(listener: AudioCapture.Listener): AudioCapture {
        val source = pcm().also { sources += it }
        return AudioCapture(source, listener = listener).also { captures += it }
    }
}

internal fun alternative(text: String, score: Double? = 120.0) = SpeechAlternative(text, score)
