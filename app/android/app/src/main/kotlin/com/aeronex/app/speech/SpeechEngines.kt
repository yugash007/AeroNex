package com.aeronex.app.speech

import java.io.File

/**
 * Opens the engines a [VoicePlan] names from verified files and the shipped grammar assets (for the T12 service
 * wiring). Loading runs on the speech thread, once per session, never per command. The native loaders are parameters
 * only so the wiring can be tested without the native libraries.
 */
object SpeechEngines {
    /**
     * The resident Vosk engine for [language] from the verified model directory ([com.aeronex.app.VoskModels]
     * runtime directory). The grammars were generated against that model's vocabulary, so any other model is refused.
     */
    fun vosk(
        language: String,
        modelDirectory: File,
        grammar: GrammarAssets,
        load: (File) -> VoskModelHandle = ::VoskNativeModel,
    ): VoskGrammarEngine {
        val entry = grammar.language(language)
        val vosk = checkNotNull(entry.vosk) { "$language has no Vosk grammar" }
        require(modelDirectory.name == vosk.model) { "The $language grammar is for ${vosk.model}, not ${modelDirectory.name}" }
        val commandGrammar = grammar.voskGrammar(language)
        val stopGrammar = grammar.voskStopGrammar(language)
        return VoskGrammarEngine(language, load(modelDirectory), commandGrammar, stopGrammar, entry.stopTokens)
    }

    /**
     * The Tier H Telugu voice stop spotter (0.4.0): [language]'s stop-only grammar on its own resident Vosk model,
     * beside an IndicConformer command engine. No command recognizer is created. Closing it closes the model; open
     * and close it on the speech thread (the model's read lease).
     */
    fun voskStop(
        language: String,
        modelDirectory: File,
        grammar: GrammarAssets,
        load: (File) -> VoskModelHandle = ::VoskNativeModel,
    ): StopSpotter {
        val entry = grammar.language(language)
        val vosk = checkNotNull(entry.vosk) { "$language has no Vosk grammar" }
        require(modelDirectory.name == vosk.model) { "The $language grammar is for ${vosk.model}, not ${modelDirectory.name}" }
        val stopGrammar = grammar.voskStopGrammar(language)
        val model = load(modelDirectory)
        val recognizer = try {
            model.recognizer(stopGrammar)
        } catch (error: Throwable) {
            VoskGrammarEngine.closeAll(listOf(model), error)
            throw error
        }
        val spotter = VoskStopSpotter(recognizer, StopTokenRule(entry.stopTokens))
        return object : StopSpotter by spotter {
            override fun close() = VoskGrammarEngine.closeAll(listOf(spotter, model), null)
        }
    }

    /** The IndicConformer engine; [SherpaCtcEngine.load] then picks the language's verified pack. */
    fun ctc(decoders: (CtcModelSpec) -> CtcDecoder = ::SherpaNativeCtc): SherpaCtcEngine = SherpaCtcEngine(decoders = decoders)

    /**
     * The keyword spotter of a verified KWS [pack] listening for [language]'s `kws_keywords` (the manifest's
     * pre-tokenised lines for that pack). [keywordsFile] is a writable app-private path (e.g. in noBackupFilesDir).
     */
    fun kws(
        pack: SpeechModelPack,
        files: Map<String, File>,
        grammar: GrammarAssets,
        language: String,
        keywordsFile: File,
        numThreads: Int = 1,
        load: (KwsModelSpec) -> KwsDecoder = ::SherpaNativeKws,
    ): SherpaKwsSpotter {
        require(pack.engine == PackEngine.KWS) { "${pack.id} is not a keyword spotter" }
        val keywords = grammar.language(language).kwsKeywords
        val lines = SherpaKwsSpotter.keywordLines(grammar.kwsLines(pack.id), keywords)
        return SherpaKwsSpotter(load(KwsModelSpec.of(files, keywordsFile, lines, numThreads)), keywords)
    }
}
