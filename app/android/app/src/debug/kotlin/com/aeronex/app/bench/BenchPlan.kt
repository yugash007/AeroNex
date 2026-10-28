package com.aeronex.app.bench

import com.aeronex.app.grammar.GrammarTables

/** The engines the bench can run: the Vosk grammar engine (+ its stop grammar), IndicConformer CTC, a KWS spotter. */
enum class UnitKind(val wire: String) { VOSK("vosk"), CTC("ctc"), KWS("kws") }

/**
 * One engine run: `vosk-<lang>` (Vosk grammar engine with its stop-grammar spotter on that language's clips),
 * `ctc-<lang>` (IndicConformer CTC) or the KWS pack id (`kws-gigaspeech-3.3M-chunk-16`: a keyword spotter on its own
 * language's clips). [packId] names the sherpa pack (null for Vosk).
 */
data class BenchUnit(val id: String, val kind: UnitKind, val language: String, val packId: String?)

/**
 * The `am start` extras: `engines` (comma list of vosk, ctc, kws; default all), `langs` (default all six), `clips`
 * (per language, default 7: one voice of every phrase) and `threads` (CTC threads; default the [com.aeronex
 * .aeronex.speech.EnginePolicy] value for this device).
 */
data class BenchRequest(
    val kinds: Set<UnitKind>,
    val languages: List<String>,
    val clipsPerLanguage: Int,
    val threads: Int?,
) {
    companion object {
        const val DEFAULT_CLIPS = 7

        fun parse(engines: String?, languages: String?, clips: Int?, threads: Int?): BenchRequest {
            val kinds = list(engines)?.map { name ->
                UnitKind.entries.firstOrNull { it.wire == name } ?: throw IllegalArgumentException("Unknown engine $name")
            }?.toSet() ?: UnitKind.entries.toSet()
            val codes = list(languages)?.onEach {
                require(it in GrammarTables.LANGUAGES) { "Unknown language $it" }
            }?.distinct() ?: GrammarTables.LANGUAGES.toList()
            require(clips == null || clips >= 1) { "At least one clip" }
            require(threads == null || threads >= 1) { "At least one thread" }
            return BenchRequest(kinds, codes, clips ?: DEFAULT_CLIPS, threads)
        }

        private fun list(text: String?): List<String>? =
            text?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
    }
}

/** The sherpa packs the app knows (assets/models/speech_packs.json): CTC pack per language, KWS packs and language. */
data class Catalogue(val ctcPacks: Map<String, String>, val kwsPacks: List<Pair<String, String>>)

/** What is installed and verified on this phone: Vosk languages and ready sherpa pack ids. */
data class Installed(val voskLanguages: Set<String>, val packs: Set<String>)

/** The units of a [BenchRequest] that can run here, and the requested ones that cannot (id → reason). */
data class BenchPlan(val units: List<BenchUnit>, val skipped: Map<String, String>) {
    companion object {
        const val NOT_INSTALLED = "not installed (push it to the import folder: tools/bench/run_bench.py)"

        fun of(request: BenchRequest, catalogue: Catalogue, installed: Installed): BenchPlan {
            val units = mutableListOf<BenchUnit>()
            val skipped = linkedMapOf<String, String>()
            fun add(unit: BenchUnit, ready: Boolean) {
                if (ready) units += unit else skipped[unit.id] = NOT_INSTALLED
            }
            if (UnitKind.VOSK in request.kinds) {
                for (code in request.languages.filter { it in GrammarTables.VOSK_LANGUAGES }) {
                    add(BenchUnit("vosk-$code", UnitKind.VOSK, code, null), code in installed.voskLanguages)
                }
            }
            if (UnitKind.CTC in request.kinds) {
                for (code in request.languages) {
                    val pack = catalogue.ctcPacks[code] ?: continue
                    add(BenchUnit("ctc-$code", UnitKind.CTC, code, pack), pack in installed.packs)
                }
            }
            if (UnitKind.KWS in request.kinds) {
                for ((pack, code) in catalogue.kwsPacks.filter { it.second in request.languages }) {
                    add(BenchUnit(pack, UnitKind.KWS, code, pack), pack in installed.packs)
                }
            }
            return BenchPlan(units, skipped)
        }
    }
}
