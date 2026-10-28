package com.aeronex.app.speech

import java.io.InputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The recognizer-side grammar assets T9 generates (`assets/grammar/grammar_manifest.json` and the files it names):
 * per language the stop lane, the keyword-spotter keywords and the Vosk command and stop grammars (plain JSON arrays
 * handed to the Recognizer as they are), plus the keyword lines per spotter model. A grammar file is used only if
 * its SHA-256 matches the manifest. [open] reads an asset path (`context.assets::open` on a phone).
 */
class GrammarAssets private constructor(private val open: (String) -> InputStream, manifest: JSONObject) {
    data class Vosk(
        /** The model directory name (`vosk-model-small-hi-0.22`). */
        val model: String,
        val grammar: String,
        val grammarSha256: String,
        val stopGrammar: String,
        val stopGrammarSha256: String,
    )

    data class Language(
        val code: String,
        /** Voice STOP lane phrases (spec 7.5, T9: never hold or hover). */
        val stopLane: List<String>,
        /** Keyword-spotter keywords ("stop" only, T9). */
        val kwsKeywords: List<String>,
        val vosk: Vosk?,
    ) {
        /** Stop tokens for the partial-result rule: the first word of each stop-lane phrase. */
        val stopTokens: Set<String> get() = StopTokenRule.tokensFor(stopLane)
    }

    val languages: Map<String, Language>
    private val kwsLines: Map<String, List<String>>

    init {
        val entries = manifest.getJSONObject("languages")
        languages = entries.keys().asSequence().associateWith { code ->
            val entry = entries.getJSONObject(code)
            val vosk = entry.optJSONObject("vosk")?.let {
                Vosk(it.getString("model"), it.getString("grammar"), it.getString("grammar_sha256"),
                    it.getString("stop_grammar"), it.getString("stop_grammar_sha256"))
            }
            Language(code, strings(entry.getJSONArray("stop_lane")), strings(entry.getJSONArray("kws_keywords")), vosk)
        }
        val lines = manifest.getJSONObject("kws").getJSONObject("lines")
        kwsLines = lines.keys().asSequence().associateWith { strings(lines.getJSONArray(it)) }
    }

    fun language(code: String): Language = languages[code] ?: throw IllegalArgumentException("No grammar for $code")

    /** The command grammar of a Vosk language, verified against its manifest hash. */
    fun voskGrammar(code: String): String = vosk(code).let { verified(it.grammar, it.grammarSha256) }

    /** The stop-only grammar of a Vosk language, verified against its manifest hash. */
    fun voskStopGrammar(code: String): String = vosk(code).let { verified(it.stopGrammar, it.stopGrammarSha256) }

    /** Pre-tokenised keyword lines for one spotter pack ("▁ST O P @stop"). */
    fun kwsLines(packId: String): List<String> =
        kwsLines[packId] ?: throw IllegalArgumentException("No keyword lines for $packId")

    private fun vosk(code: String) = checkNotNull(language(code).vosk) { "$code has no Vosk grammar" }

    private fun verified(path: String, sha256: String): String {
        val bytes = open(path).use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256) { "The grammar $path does not match its manifest" }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        const val MANIFEST = "grammar/grammar_manifest.json"

        fun load(open: (String) -> InputStream): GrammarAssets =
            GrammarAssets(open, JSONObject(open(MANIFEST).use { String(it.readBytes(), Charsets.UTF_8) }))

        private fun strings(array: JSONArray) = (0 until array.length()).map { array.getString(it) }
    }
}
