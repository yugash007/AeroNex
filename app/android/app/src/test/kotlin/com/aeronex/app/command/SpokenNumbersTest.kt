package com.aeronex.app.command

import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.NumeralKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The 0.4.0 spoken-number reader (grammar/README.md "Spoken numbers"): word2number's idea, from GrammarTables. */
class SpokenNumbersTest {
    private fun digits(text: String) = SpokenNumbers.digits(text)

    @Test fun decimalsAndHalvesBecomeDigits() {
        assertEquals("move 2.5 meters left", digits("move two point five meters left"))
        assertEquals("move left 1.5 meters", digits("move left one point five meters"))
        assertEquals("move forward 0.5 meters", digits("move forward point five meters"))
        assertEquals("move forward 2.5 meters", digits("move forward two and a half meters"))
        assertEquals("move forward 1.5 meters", digits("move forward one and half meters"))
        assertEquals("move forward 0.5 meter", digits("move forward half a meter"))
        assertEquals("move forward 0.5 metre", digits("move forward half a metre"))
        assertEquals("move forward 0.5 meter", digits("move forward a half meter"))
        assertEquals("move forward 0.5 m", digits("move forward half m"))
        // Any length, as word2number: the validator, not the reader, refuses what is out of range.
        assertEquals("go up 3.14 m", digits("go up three point one four m"))
        assertEquals("move forward 23.5 meters", digits("move forward twenty three point five meters"))
        assertEquals("take off to 10.5 m", digits("take off to ten point five m"))
        // Case and punctuation are kept outside the number.
        assertEquals("Move 2.5 Meters Left.", digits("Move Two Point Five Meters Left."))
        assertEquals("climb 1.5, then land", digits("climb one and a half, then land"))
    }

    @Test fun wholeNumbersStayWordsUnlessAsked() {
        // The rules read them already, and v0.2.0 messages quote them ("move forward twenty three meters").
        assertEquals("move forward twenty three meters", digits("move forward twenty three meters"))
        assertEquals("move forward 23 meters then 2.5 up",
            SpokenNumbers.digits("move forward twenty three meters then two point five up", integers = true))
        assertEquals("3 मीटर आगे जाओ", SpokenNumbers.digits("तीन मीटर आगे जाओ", integers = true))
    }

    @Test fun nothingIsGuessed() {
        for (text in listOf(
            "move forward half", // a bare half names no metres
            "go forward 2 meters and a half", "2 and a half मीटर आगे जाओ", // after "and": v0.2.0's refusal stays
            "move two point meters", "move forward point meters", "move forward two point fifteen meters",
            "one hundred point five", "move forward minus point", "आधा आगे जाओ", "సగం ముందుకు వెళ్ళు",
            "take off to three meters",
        )) assertEquals(text, digits(text))
    }

    @Test fun indicHalvesAreRead() {
        assertEquals("1.5 मीटर आगे जाओ", digits("डेढ़ मीटर आगे जाओ"))
        assertEquals("2.5 मीटर ऊपर जाओ", digits("ढाई मीटर ऊपर जाओ"))
        assertEquals("0.5 मीटर आगे जाओ", digits("आधा मीटर आगे जाओ"))
        assertEquals("2.5 మీటర్లు పైకి వెళ్ళు", digits("రెండున్నర మీటర్లు పైకి వెళ్ళు"))
        assertEquals("1.5 మీటర్లు ముందుకు వెళ్ళు", digits("ఒకటిన్నర మీటర్లు ముందుకు వెళ్ళు"))
        assertEquals("0.5 మీటర్ ముందుకు వెళ్ళు", digits("అర మీటర్ ముందుకు వెళ్ళు"))
        // The rule languages only by default; ta, kn and ml are their template matcher's.
        assertEquals("ஒன்றரை மீட்டர்", digits("ஒன்றரை மீட்டர்"))
        assertEquals("1.5 மீட்டர்", SpokenNumbers.digits("ஒன்றரை மீட்டர்", GrammarTables.LANGUAGES))
    }

    @Test fun theLanguagesThatReadANumberAreReported() {
        // The step parser refuses a decimal or half that another vocabulary than the step's read (README "Spoken numbers").
        val hindi = SpokenNumbers.read("move डेढ़ meters forward")
        assertEquals("move 1.5 meters forward", hindi.text)
        assertEquals(setOf("hi"), hindi.codes)
        assertEquals(setOf("en"), SpokenNumbers.read("two point five मीटर आगे जाओ").codes)
        assertEquals(setOf("te"), SpokenNumbers.read("రెండున్నర మీటర్లు పైకి వెళ్ళు").codes)
        // Whole numbers stay words, so no language read them.
        assertEquals(emptySet<String>(), SpokenNumbers.read("move forward three meters").codes)
        assertEquals(setOf("en"), SpokenNumbers.read("move forward three meters", integers = true).codes)
    }

    @Test fun everyGeneratedNumeralReadsBackWithItsValue() {
        // The generator's composed and listed numerals and the Kotlin reader agree (README "Spoken numbers").
        for (lexicon in GrammarTables.lexicons.values) {
            for (numeral in lexicon.numerals) {
                if (numeral.kind == NumeralKind.LOAN || numeral.kind == NumeralKind.REJECT) continue
                assertEquals("${lexicon.code} ${numeral.phrase}", numeral.value,
                    SpokenNumbers.value(numeral.phrase, lexicon.code)!!, 1e-9)
            }
        }
        assertEquals(3.14, SpokenNumbers.value("three point one four", "en")!!, 1e-9)
        assertNull(SpokenNumbers.value("one hundred", "en")) // a scale word: refused, as v0.2.0
        assertNull(SpokenNumbers.value("two three", "en"))
        assertNull(SpokenNumbers.value("five", "te"))
    }
}
