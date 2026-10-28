package com.aeronex.app.speech

import com.aeronex.app.VoskModels
import com.aeronex.app.safety.MiniJson
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Builds the engine specs exactly as the app does (pack manifest, grammar manifest, EnginePolicy threads) over the
 * T3 tooling copies of the model files. With `AERONEX_T11_SPEC_EXPORT=<file>` it writes them as JSON for the host
 * smoke (scratchpad t11/smoke/engine_smoke.py), which decodes the eSpeak clips with those values in sherpa-onnx and
 * Vosk. Skipped where the tooling folder is absent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EngineSpecExportTest {
    private val tooling = File("../../../../tooling").canonicalFile
    private val packs = SpeechPackManifest.parse(File("src/main/assets/${SpeechPackManifest.ASSET}").readText())
    private val grammar = GrammarAssets.load { File("src/main/assets/$it").inputStream() }
    /** A 4 GiB arm64 phone: Tier M, the tier that runs CTC by default. */
    private val policy = EnginePolicy(DeviceFacts(listOf("arm64-v8a"), 4L shl 30, false, 8))

    /** The tooling path of each pack file, by role (the store would hold the same verified bytes). */
    private fun toolingFiles(pack: SpeechModelPack): Map<String, File> {
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(File(tooling, "models/sherpa/manifest.json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val paths = (root["packs"] as List<Map<String, Any?>>).single { it["id"] == pack.id }["files"] as List<String>
        return pack.files.zip(paths).associate { (file, path) ->
            assertEquals(file.name, path.substringAfterLast('/'))
            file.role to File(tooling, path)
        }
    }

    @Test fun specsComeFromTheShippedManifestsAndExportForTheHostSmoke() {
        assumeTrue("tooling present", File(tooling, "models/sherpa/manifest.json").isFile)
        val ctc = JSONArray()
        for (pack in packs.filter { it.engine == PackEngine.NEMO_CTC }) {
            val plan = policy.plan(pack.language, AvailableEngines(emptySet(), packs))
            assertEquals(CommandEngineChoice.Ctc(pack), plan.command)
            val spec = CtcModelSpec.of(pack, toolingFiles(pack), plan.asrThreads)
            assertTrue(spec.model.isFile && spec.tokens.isFile)
            ctc.put(JSONObject().put("pack", pack.id).put("language", spec.language).put("model", spec.model.path)
                .put("tokens", spec.tokens.path).put("numThreads", spec.numThreads).put("sampleRate", spec.sampleRate)
                .put("featureDim", spec.featureDim).put("dither", spec.dither.toDouble())
                .put("decodingMethod", spec.decodingMethod).put("provider", spec.provider))
        }
        val kws = JSONArray()
        for (pack in packs.filter { it.engine == PackEngine.KWS }) {
            val keywords = SherpaKwsSpotter.keywordLines(grammar.kwsLines(pack.id), grammar.language("en").kwsKeywords)
            val spec = KwsModelSpec.of(toolingFiles(pack), File("keywords-${pack.id}.txt"), keywords, policy.kwsThreads)
            kws.put(JSONObject().put("pack", pack.id).put("defaultEnabled", pack.defaultEnabled)
                .put("encoder", spec.encoder.path).put("decoder", spec.decoder.path).put("joiner", spec.joiner.path)
                .put("tokens", spec.tokens.path).put("keywordsFileText", spec.keywordsFileText)
                .put("numThreads", spec.numThreads).put("keywordsThreshold", spec.keywordsThreshold.toDouble())
                .put("keywordsScore", spec.keywordsScore.toDouble()).put("numTrailingBlanks", spec.numTrailingBlanks)
                .put("maxActivePaths", spec.maxActivePaths).put("sampleRate", spec.sampleRate)
                .put("featureDim", spec.featureDim).put("provider", spec.provider)
                .put("chunkSamples", SherpaKwsSpotter.CHUNK_SAMPLES))
        }
        val vosk = JSONArray()
        for (language in listOf("en", "hi", "te")) {
            val entry = grammar.language(language)
            val model = VoskModels.profiles.getValue(VoskModels.profileForLanguage(language)!!).runtimeFile
            assertEquals(model, entry.vosk!!.model)
            vosk.put(JSONObject().put("language", language).put("model", File(tooling, "vosk/$model").path)
                .put("grammar", File("src/main/assets/${entry.vosk.grammar}").canonicalPath)
                .put("stopGrammar", File("src/main/assets/${entry.vosk.stopGrammar}").canonicalPath)
                .put("stopTokens", JSONArray(entry.stopTokens.toList()))
                .put("maxAlternatives", VoskGrammarEngine.MAX_ALTERNATIVES).put("partialWords", false)
                .put("stepSamples", VoskGrammarEngine.STEP_SAMPLES).put("stopSteps", 2))
        }
        assertEquals(4, ctc.length())
        assertEquals(2, kws.length())
        val target = System.getenv("AERONEX_T11_SPEC_EXPORT") ?: return
        File(target).writeText(JSONObject().put("ctc", ctc).put("kws", kws).put("vosk", vosk).toString(1))
    }
}
