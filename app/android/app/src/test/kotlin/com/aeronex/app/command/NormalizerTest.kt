package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.NumeralKind
import com.aeronex.app.safety.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class NormalizerTest {
    private fun cp(vararg points: Int) = String(points, 0, points.size)

    @Test fun nfcTurnsAPrecomposedNuktaLetterIntoTheTablesSpelling() {
        val precomposed = cp(0x091F, 0x0947, 0x0915, 0x0911, 0x095E) // टेकऑफ़ with U+095E FA
        val decomposed = cp(0x091F, 0x0947, 0x0915, 0x0911, 0x092B, 0x093C) // फ + nukta, as in the tables
        assertEquals(decomposed, Normalizer.clean(precomposed))
        assertEquals(decomposed, Normalizer.clean(decomposed))
        assertTrue(decomposed in GrammarTables.lexicon("hi").commands.flatMap { it.phrases })
        val plan = PlanParser.parse("3 मीटर $precomposed करो", "hi-IN") as ParseResult.Accepted
        assertEquals("takeoff({altitude_m: 3})", plan.plan.steps.single().command.toString())
    }

    @Test fun zeroWidthJoinersAreRemoved() {
        assertEquals("డ్రోన్ను", Normalizer.clean("డ్రోన్" + cp(0x200C) + "ను"))
        assertEquals("क्ष", Normalizer.clean("क्" + cp(0x200D) + "ष"))
        assertEquals("ಮೀಟರ್ಗಳು", Normalizer.clean("ಮೀಟರ್" + cp(0x200C) + "ಗಳು"))
    }

    @Test fun strayNuktasAndOrphanCombiningMarksAreDropped() {
        val da = cp(0x0921)
        val nukta = cp(0x093C)
        assertEquals(cp(0x095C).let(Normalizer::clean), Normalizer.clean(da + nukta + nukta)) // ड़ with a doubled nukta
        assertEquals(cp(0x092B, 0x093C), Normalizer.clean(cp(0x095E, 0x093C))) // precomposed फ़ plus a stray nukta
        assertEquals("का", Normalizer.clean("का$nukta")) // after a vowel sign
        assertEquals("", Normalizer.clean(nukta))
        assertEquals("ల్యాండ్", Normalizer.clean(cp(0x0C3E) + "ల్యాండ్")) // a CTC hypothesis opening with a vowel sign
        assertEquals("a b", Normalizer.clean("a " + cp(0x0947) + "b"))
        for (phrase in GrammarTables.lexicon("hi").commands.flatMap { it.phrases }) assertEquals(phrase, Normalizer.clean(phrase))
    }

    @Test fun loanwordsBecomeTheirNativeFormAndTheMapIsTwoWay() {
        assertEquals("तीन मीटर टेकऑफ करो", Normalizer.loanwords("तीन मीटर्स टेक ऑफ करो"))
        assertEquals("మూడు మీటర్లు టేకాఫ్", Normalizer.loanwords("మూడు మీటర్స్ టేక్  ఆఫ్"))
        assertEquals("status", Normalizer.loanwords("स्टेटस")) // no native form: the Latin word
        assertEquals("गोल", Normalizer.loanwords("गोल")) // गो ("go") inside another word stays
        assertEquals("take off", Normalizer.latin("టేక్ ఆఫ్"))
        assertNull(Normalizer.latin("ల్యాండ్"))
        assertEquals(listOf("టేక్ ఆఫ్"), Normalizer.loans("take off", "te"))
    }

    @Test fun everyNumeralOfEveryLanguageReadsBackWithItsValueAndKind() {
        for (lexicon in GrammarTables.lexicons.values) {
            val first = lexicon.numerals.groupBy { Normalizer.clean(it.phrase) }.mapValues { it.value.first() }
            for ((phrase, numeral) in first) {
                val read = Normalizer.number(phrase, lexicon.code)
                assertEquals("${lexicon.code} $phrase", numeral.value, read!!.value, 0.0)
                assertEquals("${lexicon.code} $phrase", numeral.kind, read.kind)
            }
            for (case in fixtures(lexicon.code)["numbers"] as List<Map<String, Any?>>) {
                val read = Normalizer.number(case["phrase"] as String, lexicon.code)
                assertEquals("${lexicon.code} ${case["phrase"]}", (case["value"] as Number).toDouble(), read!!.value, 0.0)
                assertEquals("${lexicon.code} ${case["phrase"]}", case["kind"], read.kind.name)
            }
            // Every value 0-100 and every half has a numeral (spec 7.4).
            val values = lexicon.numerals.map { it.value }.toSet()
            for (value in (0..100).map(Int::toDouble) + listOf(0.5, 1.5, 2.5)) assertTrue("${lexicon.code} $value", value in values)
        }
    }

    @Test fun digitsOfEveryScriptAreNumbersAndNothingElseIsGuessed() {
        for (lexicon in GrammarTables.lexicons.values) {
            for (digit in 0..9) {
                assertEquals(digit.toDouble(), Normalizer.number(cp(lexicon.digitZero + digit), lexicon.code)!!.value, 0.0)
            }
            assertEquals(42.0, Normalizer.number(cp(lexicon.digitZero + 4, lexicon.digitZero + 2), lexicon.code)!!.value, 0.0)
        }
        assertEquals(2.5, Normalizer.number("2.5", "te")!!.value, 0.0)
        assertEquals(NumeralKind.RULE, Normalizer.number("7", "en")!!.kind)
        assertNull(Normalizer.number("ఐదో", "te")) // a near miss of ఐదు is no number
        assertNull(Normalizer.number("fiveteen", "en"))
        assertNull(Normalizer.number("five", "te")) // Latin is not in the te tables
        assertNull(Normalizer.number("2.", "en"))
        assertEquals("123 m", Normalizer.asciiDigits(cp(0x0BE7, 0x0BE8, 0x0BE9) + " m")) // Tamil digits
    }

    @Test fun languageCodes() {
        assertEquals("te", Normalizer.languageCode("te-IN"))
        assertEquals("hi", Normalizer.languageCode("hi"))
        assertEquals("en", Normalizer.languageCode("en_IN"))
        assertEquals("ml", Normalizer.languageCode("Malayalam"))
        assertNull(Normalizer.languageCode("fr-FR"))
    }

    private fun fixtures(code: String): Map<String, Any?> {
        val stream = javaClass.getResourceAsStream("/grammar/fixtures_$code.json") ?: error("fixtures_$code.json missing")
        return MiniJson.parse(stream.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>
    }
}
