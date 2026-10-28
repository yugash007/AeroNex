package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing
import java.io.File

/**
 * Everything the sherpa-onnx adapter passes to `KeywordSpotterConfig`: the W1 bench settings (1 thread, threshold
 * 0.25, score 1.0, 1 trailing blank, 4 active paths, 16 kHz, 80-dim features). [keywords] are pre-tokenised lines
 * from grammar_manifest.json (`kws.lines[packId]`), written to [keywordsFile] before the spotter loads.
 */
data class KwsModelSpec(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val keywordsFile: File,
    val keywords: List<String>,
    val numThreads: Int = 1,
    val keywordsThreshold: Float = 0.25f,
    val keywordsScore: Float = 1.0f,
    val numTrailingBlanks: Int = 1,
    val maxActivePaths: Int = 4,
    val sampleRate: Int = AudioRing.SAMPLE_RATE,
    val featureDim: Int = 80,
    val provider: String = "cpu",
) {
    init {
        require(keywords.isNotEmpty()) { "No keywords" }
    }

    val keywordsFileText: String get() = keywords.joinToString("\n", postfix = "\n")

    companion object {
        /** The spec of a verified KWS pack's files (`encoder`, `decoder`, `joiner`, `tokens` roles). */
        fun of(files: Map<String, File>, keywordsFile: File, keywords: List<String>, numThreads: Int = 1) =
            KwsModelSpec(files.getValue("encoder"), files.getValue("decoder"), files.getValue("joiner"),
                files.getValue("tokens"), keywordsFile, keywords, numThreads)
    }
}

/** One loaded keyword spotter with its stream. */
interface KwsDecoder : AutoCloseable {
    /** Feeds one chunk of float samples; returns the keyword label found (the stream restarts after a hit) or null. */
    fun accept(samples: FloatArray): String?

    /** Starts a fresh stream (between VAD segments). */
    fun reset()
}

/**
 * The sherpa-path voice stop spotter (spec 4.12; Tier M/H, and Tier L when the session runtime is sherpa): an
 * English keyword spotter (gigaspeech 3.3M chunk-16 by default, W1) listening for the language's `kws_keywords`
 * ("stop" only, T9: "hold" is a PTT command, never a STOP). Audio goes in 100 ms float chunks; a label outside
 * [keywords] never fires.
 */
class SherpaKwsSpotter(
    private val decoder: KwsDecoder,
    keywords: Collection<String>,
) : StopSpotter {
    private val keywords = keywords.toSet()
    private val chunk = FloatArray(CHUNK_SAMPLES)
    private var filled = 0

    override fun accept(samples: ShortArray, offset: Int, count: Int): Boolean {
        require(offset >= 0 && count >= 0 && offset + count <= samples.size) { "Bad sample range" }
        var index = offset
        val end = offset + count
        while (index < end) {
            val n = minOf(chunk.size - filled, end - index)
            for (i in 0 until n) chunk[filled + i] = samples[index + i] / 32768f
            filled += n
            index += n
            if (filled == chunk.size) {
                filled = 0
                if (decoder.accept(chunk) in this.keywords) return true
            }
        }
        return false
    }

    override fun reset() {
        filled = 0
        decoder.reset()
    }

    override fun close() {
        decoder.close()
    }

    companion object {
        const val CHUNK_SAMPLES = 1600

        /** The manifest's keyword lines for one spotter model, kept to those labelled with one of [kwsKeywords]. */
        fun keywordLines(manifestLines: List<String>, kwsKeywords: List<String>): List<String> {
            val lines = manifestLines.filter { line -> line.substringAfterLast('@', "").trim() in kwsKeywords }
            val labels = lines.map { it.substringAfterLast('@').trim() }.toSet()
            require(labels == kwsKeywords.toSet()) { "No keyword line for ${kwsKeywords - labels}" }
            return lines
        }
    }
}
