package com.aeronex.app.grammar

import com.aeronex.app.safety.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer

/**
 * GrammarTables.kt, the grammar assets and the CI fixtures come from one generator run (tools/grammar-gen/gen.py,
 * grammar/README.md "Kotlin"). These tests check that they agree, and that the tables keep the spec's rules:
 * numbers 0-100 with the halves (7.4; read as their value since 0.4.0), negations a Vosk grammar can decode (4.8)
 * and a STOP-only spotter (W1).
 */
@Suppress("UNCHECKED_CAST")
class GrammarTablesTest {
    private val all = GrammarTables.LANGUAGES.map(GrammarTables::lexicon)

    @Test
    fun `six languages with their engines`() {
        assertEquals(listOf("en", "hi", "te", "ta", "kn", "ml"), GrammarTables.LANGUAGES)
        assertEquals(listOf("en", "hi", "te"), GrammarTables.RULE_LANGUAGES)
        assertEquals(listOf("en", "hi", "te"), GrammarTables.VOSK_LANGUAGES)
        assertEquals(listOf("te", "ta", "kn", "ml"), GrammarTables.CTC_LANGUAGES)
        assertEquals(listOf("forward", "backward", "left", "right", "up", "down"), GrammarTables.DIRECTIONS)
        for (lexicon in all) {
            assertEquals(lexicon.code in GrammarTables.RULE_LANGUAGES, lexicon.ruleVocabulary)
            assertEquals(lexicon.code in listOf("ta", "kn", "ml"), lexicon.provisional)
            assertEquals(GrammarTables.DIRECTIONS, lexicon.directions.keys.toList())
        }
        assertTrue(runCatching { GrammarTables.lexicon("xx") }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `every language holds 0 to 100 and reads the halves as their value`() {
        // 0.4.0 (grammar/README.md "Spoken numbers"): the halves were refused before; now they are FRACTION.
        for (lexicon in all) {
            val values = lexicon.numerals.filter { it.kind != NumeralKind.LOAN }.map { it.value }.toSet()
            val missing = (0..100).filter { it.toDouble() !in values }
            assertTrue("${lexicon.code} lacks $missing", missing.isEmpty())
            val halves = lexicon.numerals.filter { it.kind == NumeralKind.FRACTION }.map { it.value }.toSet()
            assertTrue(lexicon.code, halves.containsAll(listOf(0.5, 1.5, 2.5)))
            assertTrue(lexicon.code, lexicon.numerals.none { it.value % 1 != 0.0 && it.kind != NumeralKind.FRACTION })
            assertTrue(lexicon.code, lexicon.numerals.none { it.kind == NumeralKind.REJECT && it.value % 1 != 0.0 })
            val byPhrase = lexicon.numerals.groupBy { it.phrase }
            assertTrue(lexicon.code, byPhrase.values.all { it.size == 1 })
        }
    }

    @Test
    fun `rule numerals are the v0_2_0 number tables`() {
        for (code in GrammarTables.RULE_LANGUAGES) {
            val lexicon = GrammarTables.lexicon(code)
            val words = lexicon.ones.keys + lexicon.tens.keys + lexicon.numbers.keys
            assertEquals(code, words, GrammarTables.Plan.NUMERAL_WORDS.getValue(code))
            val rule = lexicon.numerals.filter { it.kind == NumeralKind.RULE }.associate { it.phrase to it.value }
            for ((word, value) in lexicon.ones + lexicon.tens + lexicon.numbers) {
                if (word !in lexicon.scaleWords) assertEquals("$code $word", value.toDouble(), rule[word])
            }
        }
        assertEquals(23.0, numeral("en", "twenty three"), 0.0)
        assertEquals(23.0, numeral("te", "ఇరవై మూడు"), 0.0)
        assertEquals(15.0, numeral("hi", "पंद्रह"), 0.0)
        assertEquals(NumeralKind.FRACTION, GrammarTables.lexicon("hi").numerals.single { it.phrase == "ढाई" }.kind)
        assertEquals(NumeralKind.REJECT, GrammarTables.lexicon("en").numerals.single { it.phrase == "one hundred" }.kind)
    }

    @Test
    fun `English composes spoken decimals below ten and has the natural phrasing tables`() {
        val en = GrammarTables.lexicon("en")
        assertEquals(2.5, numeral("en", "two point five"), 0.0)
        assertEquals(9.9, numeral("en", "nine point nine"), 1e-9)
        assertEquals(4.5, numeral("en", "four and a half"), 0.0)
        assertEquals(0.5, numeral("en", "half a"), 0.0)
        assertTrue(en.numerals.none { it.phrase == "ten point five" })
        assertEquals(listOf("point"), en.decimalPoints)
        assertEquals(listOf("and a half", "and half"), en.andHalf)
        assertEquals(listOf("climb", "ascend", "rise"), en.directionVerbs["up"])
        assertEquals(listOf("reverse", "retreat", "back up"), en.directionVerbs["backward"])
        assertEquals(mapOf("up" to listOf("higher"), "down" to listOf("lower")), en.comparatives)
        for (lexicon in all) {
            assertTrue(lexicon.code, GrammarTables.DIRECTIONS.containsAll(lexicon.directionVerbs.keys + lexicon.comparatives.keys))
            assertEquals(lexicon.code, lexicon.directions.getValue("left") + lexicon.directions.getValue("right"),
                lexicon.classes.getValue("side"))
            if (lexicon.code == "en") continue
            assertTrue(lexicon.code, lexicon.decimalPoints.isEmpty() && lexicon.andHalf.isEmpty())
            assertTrue(lexicon.code, lexicon.directionVerbs.isEmpty() && lexicon.comparatives.isEmpty())
        }
    }

    @Test
    fun `the voice STOP lane holds stop words only and the spotter listens for stop only`() {
        assertEquals(listOf("stop"), GrammarTables.KWS_KEYWORDS)
        for (lexicon in all) {
            assertEquals(lexicon.code, listOf("stop"), lexicon.kwsKeywords)
            val stops = lexicon.commands.single { it.command == "STOP" }.phrases +
                lexicon.loanwords.filter { it.wordClass == "stop" }.map { it.loan }
            val holds = lexicon.classes.getValue("hold")
            assertTrue(lexicon.code, lexicon.stopLane.isNotEmpty() && stops.containsAll(lexicon.stopLane))
            assertTrue(lexicon.code, lexicon.stopLane.none { it in holds || it == "hold" || it == "hover" })
        }
    }

    @Test
    fun `stop grammars hold no decoy that sounds like a stop word`() {
        // grammar/README.md "Stop lane and keyword spotting": a near-stop decoy would absorb a spoken STOP.
        val nearStop = mapOf(
            "en" to listOf("start", "started", "step", "steps", "stuff", "top", "drop"),
            "hi" to listOf("रूप"),
            "te" to listOf("ఆరు", "అప్", "స్టార్"),
        )
        for ((code, words) in nearStop) {
            val stop = (json(asset("stop_$code.json")) as List<String>).toSet()
            assertTrue(code, words.none { it in stop })
            assertTrue(code, stop.containsAll(GrammarTables.lexicon(code).stopLane))
        }
        val vosk = (json(asset("vosk_te.json")) as List<String>).toSet()
        assertTrue("ముందుకు వెళ్ళకు" in vosk && "ఆపకు" !in vosk)
    }

    @Test
    fun `templates use known classes and every direction form names a direction`() {
        for (lexicon in all) {
            assertTrue(lexicon.code, lexicon.templates.map { it.intent }.containsAll(
                listOf("takeoff", "move", "hold", "stop", "land", "status")))
            for (template in lexicon.templates) for (token in template.tokens) {
                if (token.startsWith("{")) assertTrue("${lexicon.code} $token", token.trim('{', '}') in lexicon.classes)
            }
            val forms = lexicon.directions.values.flatten().toSet()
            for (form in lexicon.classes.getValue("dir")) {
                val native = lexicon.loanwords.singleOrNull { it.loan == form }?.native ?: form
                assertTrue("${lexicon.code} $form", native in forms)
            }
        }
    }

    @Test
    fun `every table string is NFC without stray spaces`() {
        val strings = mutableListOf<String>()
        for (lexicon in all) {
            strings += lexicon.moveVerbs + lexicon.directions.values.flatten() + lexicon.units + lexicon.negators +
                lexicon.commands.flatMap { it.phrases } + lexicon.ones.keys + lexicon.numbers.keys +
                lexicon.grammarNegators + lexicon.stopLane + lexicon.numerals.map { it.phrase } +
                lexicon.loanwords.map { it.loan } + lexicon.classes.values.flatten()
        }
        strings += GrammarTables.Plan.CONNECTIVES + GrammarTables.Plan.INDIC_FILLER + GrammarTables.Plan.COURTESY +
            GrammarTables.Transcript.FUSED_NEGATORS + GrammarTables.Transcript.NEGATORS
        for (s in strings) {
            assertTrue(s, Normalizer.isNormalized(s, Normalizer.Form.NFC))
            assertTrue(s, s.isNotEmpty() && s == s.trim() && "  " !in s)
        }
    }

    @Test
    fun `the assets come from the same grammar source as the tables`() {
        val manifest = json(asset("grammar_manifest.json")) as Map<String, Any?>
        assertEquals(GrammarTables.SOURCE_SHA256, manifest["source_sha256"])
        assertEquals(GrammarTables.VERSION.toLong(), manifest["version"])
        assertEquals(listOf("stop"), (manifest["kws"] as Map<String, Any?>)["keywords"])
        val languages = manifest["languages"] as Map<String, Map<String, Any?>>
        assertEquals(GrammarTables.LANGUAGES.toSet(), languages.keys)
        for (lexicon in all) {
            val info = languages.getValue(lexicon.code)
            assertEquals(listOf("stop"), info["kws_keywords"])
            assertEquals(lexicon.stopLane, info["stop_lane"])
            val vosk = info["vosk"] as Map<String, Any?>?
            val ctc = info["ctc"] as Map<String, Any?>?
            if (lexicon.code in GrammarTables.VOSK_LANGUAGES) {
                assertNotNull(vosk)
                assertEquals(sha256(asset("vosk_${lexicon.code}.json")), vosk!!["grammar_sha256"])
                assertEquals(sha256(asset("stop_${lexicon.code}.json")), vosk["stop_grammar_sha256"])
            } else {
                assertNull(vosk)
            }
            if (lexicon.code in GrammarTables.CTC_LANGUAGES) {
                assertEquals(sha256(asset("ctc_lexicon_${lexicon.code}.json")), ctc!!["lexicon_sha256"])
            } else {
                assertNull(ctc)
            }
        }
    }

    @Test
    fun `Vosk grammars hold every fixture command, negated command and 2-step plan as word pairs`() {
        for (code in GrammarTables.VOSK_LANGUAGES) {
            val phrases = (json(asset("vosk_$code.json")) as List<String>).toSet()
            val stop = (json(asset("stop_$code.json")) as List<String>).toSet()
            assertTrue(code, "[unk]" in phrases && "[unk]" in stop)
            assertTrue(code, stop.containsAll(GrammarTables.lexicon(code).stopLane))
            val fixtures = fixtures(code)
            val cases = (fixtures["positive"] as List<Map<String, Any?>>) +
                (fixtures["negated"] as List<Map<String, Any?>>) + (fixtures["plans"] as List<Map<String, Any?>>)
            for (case in cases) {
                val words = (case["text"] as String).split(" ")
                val needed = if (words.size == 1) words else words.zipWithNext { a, b -> "$a $b" }
                assertTrue("$code ${case["text"]}: ${needed - phrases}", phrases.containsAll(needed))
            }
        }
    }

    @Test
    fun `fixtures agree with the tables`() {
        for (lexicon in all) {
            val fixtures = fixtures(lexicon.code)
            val numbers = (fixtures["numbers"] as List<Map<String, Any?>>)
                .map { Triple(it["phrase"], (it["value"] as Number).toDouble(), it["kind"]) }
            assertEquals(lexicon.code, lexicon.numerals.map { Triple(it.phrase, it.value, it.kind.name) }, numbers)
            for (case in fixtures["positive"] as List<Map<String, Any?>>) {
                if (case["intent"] != "move") continue
                val direction = (case["arguments"] as Map<String, Any?>)["direction"] as String
                val text = case["text"] as String
                val forms = lexicon.directions.getValue(direction) + lexicon.loanwords
                    .filter { it.wordClass == "dir" && it.native in lexicon.directions.getValue(direction) }
                    .map { it.loan } + lexicon.directionVerbs[direction].orEmpty() + lexicon.comparatives[direction].orEmpty()
                assertTrue("${lexicon.code} $text", forms.any { " $it " in " $text " })
            }
            assertFalse(lexicon.code, (fixtures["negated"] as List<*>).isEmpty())
            val plans = fixtures["plans"] as List<Map<String, Any?>>
            assertFalse(lexicon.code, plans.isEmpty())
            for (plan in plans) {
                val text = plan["text"] as String
                val steps = plan["steps"] as List<Map<String, Any?>>
                assertEquals("${lexicon.code} $text", listOf("move_relative", "land"), steps.map { it["tool"] })
                val connective = GrammarTables.Plan.CONNECTIVES.filter { " $it " in text }
                assertTrue("${lexicon.code} $text", connective.isNotEmpty())
            }
        }
    }

    private fun numeral(code: String, phrase: String) =
        GrammarTables.lexicon(code).numerals.single { it.phrase == phrase }.value

    private fun fixtures(code: String): Map<String, Any?> {
        val stream = javaClass.getResourceAsStream("/grammar/fixtures_$code.json")
            ?: error("test resource grammar/fixtures_$code.json is missing; run python tools/grammar-gen/gen.py")
        return json(stream.use { it.readBytes() }) as Map<String, Any?>
    }

    /** A generated asset, read in place: Gradle runs unit tests in app/android/app; the lookup walks up from there. */
    private fun asset(name: String): ByteArray {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (prefix in listOf("src/main/assets/grammar", "app/android/app/src/main/assets/grammar")) {
                val file = File(dir, "$prefix/$name")
                if (file.isFile) return file.readBytes()
            }
            dir = dir.parentFile
        }
        error("assets/grammar/$name not found above ${System.getProperty("user.dir")}")
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun json(bytes: ByteArray): Any? = MiniJson.parse(String(bytes, Charsets.UTF_8))
}
