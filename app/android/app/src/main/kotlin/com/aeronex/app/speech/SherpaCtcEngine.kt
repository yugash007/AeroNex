package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing
import java.io.File

/**
 * Everything the sherpa-onnx adapter passes to `OfflineRecognizerConfig` for an IndicConformer NeMo CTC pack, and
 * nothing else: the W1 host bench configuration (`from_nemo_ctc`: 16 kHz, 80-dim features, no dither, greedy search,
 * CPU). [numThreads] comes from [EnginePolicy.asrThreads].
 */
data class CtcModelSpec(
    val language: String,
    val model: File,
    val tokens: File,
    val numThreads: Int,
    val sampleRate: Int = AudioRing.SAMPLE_RATE,
    val featureDim: Int = 80,
    val dither: Float = 0f,
    val decodingMethod: String = "greedy_search",
    val provider: String = "cpu",
) {
    init {
        require(numThreads >= 1) { "At least one ASR thread" }
    }

    companion object {
        /** The spec of a verified pack's files (`model`, `tokens` roles). */
        fun of(pack: SpeechModelPack, files: Map<String, File>, numThreads: Int) =
            CtcModelSpec(pack.language, files.getValue("model"), files.getValue("tokens"), numThreads)
    }
}

/** One loaded CTC model: decodes a whole utterance of float samples in [-1, 1) at 16 kHz. */
interface CtcDecoder : AutoCloseable {
    fun decode(samples: FloatArray): String
}

/**
 * IndicConformer CTC through sherpa-onnx (spec 4.6): non-streaming, so it buffers the utterance and decodes once
 * after PTT release + tail. One CTC model is resident at a time ([load] releases the previous language's model before
 * loading the next: about 300 MiB of heap each, W1). No partials, so no partial-token STOP (spec 4.12 gap).
 *
 * [decoders] builds the native decoder ([SherpaNativeCtc] on a phone). The utterance buffer is preallocated
 * ([maxUtteranceMs], default 10 s including pre-roll and tail); a longer utterance is refused, never decoded in part,
 * because a truncated command could still parse into a shorter valid plan.
 */
class SherpaCtcEngine(
    maxUtteranceMs: Int = MAX_UTTERANCE_MS,
    private val decoders: (CtcModelSpec) -> CtcDecoder,
) : SpeechEngine {
    override val kind = EngineKind.SHERPA_CTC

    private val buffer = FloatArray(AudioRing.samplesForMs(maxUtteranceMs))
    private var count = 0
    private var heard = 0L
    private var spec: CtcModelSpec? = null
    private var decoder: CtcDecoder? = null

    override val language: String get() = spec?.language.orEmpty()

    /** Makes [next] the resident model (no-op when it already is). On failure no model is resident. */
    fun load(next: CtcModelSpec) {
        if (next == spec && decoder != null) return
        unload()
        decoder = decoders(next)
        spec = next
    }

    override fun begin() {
        count = 0
        heard = 0
    }

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "Bad sample range" }
        heard += count
        val room = minOf(count, buffer.size - this.count)
        for (i in 0 until room) buffer[this.count + i] = samples[offset + i] / 32768f
        this.count += room
        return false
    }

    override fun finish(): SpeechResult {
        val started = System.nanoTime()
        fun result(alternatives: List<SpeechAlternative>, failure: SpeechResult.Failure?) =
            SpeechResult(kind, language, alternatives, heard, System.nanoTime() - started, failure)
        val current = decoder ?: return result(emptyList(), SpeechResult.Failure.NO_MODEL)
        if (heard > buffer.size) return result(emptyList(), SpeechResult.Failure.TOO_LONG)
        val text = try {
            current.decode(buffer.copyOf(count))
        } catch (error: RuntimeException) {
            return result(emptyList(), SpeechResult.Failure.ENGINE_ERROR)
        }
        val trimmed = text.trim()
        return result(if (trimmed.isEmpty()) emptyList() else listOf(SpeechAlternative(trimmed, null)), null)
    }

    override fun cancel() {
        begin()
    }

    override fun close() {
        unload()
    }

    private fun unload() {
        val current = decoder
        decoder = null
        spec = null
        current?.close()
    }

    companion object {
        const val MAX_UTTERANCE_MS = 10_000
    }
}
