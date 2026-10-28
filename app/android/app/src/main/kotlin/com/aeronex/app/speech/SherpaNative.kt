package com.aeronex.app.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/*
 * The only file that touches the sherpa-onnx AAR (1.13.8). Every sherpa class loads libsherpa-onnx-jni in its
 * companion, so JVM tests never construct these; they use the CtcDecoder/KwsDecoder fakes. The configs are built
 * from CtcModelSpec/KwsModelSpec field for field; the T11 host smoke decodes the eSpeak clips with the same values.
 * A null AssetManager makes sherpa read plain file paths (the verified pack directory).
 */

/** IndicConformer NeMo CTC on sherpa-onnx `OfflineRecognizer`. */
class SherpaNativeCtc(private val spec: CtcModelSpec) : CtcDecoder {
    private val recognizer = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(spec.sampleRate, spec.featureDim, spec.dither),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = spec.model.absolutePath),
                tokens = spec.tokens.absolutePath,
                numThreads = spec.numThreads,
                debug = false,
                provider = spec.provider,
            ),
            decodingMethod = spec.decodingMethod,
        ),
    )

    override fun decode(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, spec.sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() {
        recognizer.release()
    }
}

/** A sherpa-onnx `KeywordSpotter` with one stream; keywords come from [KwsModelSpec.keywordsFile]. */
class SherpaNativeKws(private val spec: KwsModelSpec) : KwsDecoder {
    private val spotter: KeywordSpotter
    private var stream: OnlineStream

    init {
        spec.keywordsFile.parentFile?.mkdirs()
        spec.keywordsFile.writeText(spec.keywordsFileText)
        spotter = KeywordSpotter(
            assetManager = null,
            config = KeywordSpotterConfig(
                featConfig = FeatureConfig(spec.sampleRate, spec.featureDim, 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = spec.encoder.absolutePath,
                        decoder = spec.decoder.absolutePath,
                        joiner = spec.joiner.absolutePath,
                    ),
                    tokens = spec.tokens.absolutePath,
                    numThreads = spec.numThreads,
                    debug = false,
                    provider = spec.provider,
                ),
                maxActivePaths = spec.maxActivePaths,
                keywordsFile = spec.keywordsFile.absolutePath,
                keywordsScore = spec.keywordsScore,
                keywordsThreshold = spec.keywordsThreshold,
                numTrailingBlanks = spec.numTrailingBlanks,
            ),
        )
        stream = spotter.createStream("")
    }

    override fun accept(samples: FloatArray): String? {
        stream.acceptWaveform(samples, spec.sampleRate)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val keyword = spotter.getResult(stream).keyword
            if (keyword.isNotBlank()) {
                spotter.reset(stream)
                return keyword
            }
        }
        return null
    }

    override fun reset() {
        stream.release()
        stream = spotter.createStream("")
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
