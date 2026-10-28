package com.aeronex.app.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 0.4.0 lexicon gloss: hi/te/ta/kn/ml command words to English through GrammarTables, before the AI planner. */
class GlossTest {
    private fun english(text: String) = Gloss.english(text).also {
        assertTrue("$text: unknown ${it.unknown}", it.unknown.isEmpty())
    }.text

    @Test fun hindiNumbersDirectionsVerbsAndConnectives() {
        assertEquals("3 meters forward move then land", english("तीन मीटर आगे जाओ और फिर उतरो"))
        assertEquals("1.5 meters up move", english("डेढ़ मीटर ऊपर जाओ"))
        // The light verb "कर दो" is "करो", never the number two.
        assertEquals("land", english("लैंड कर दो"))
        assertEquals("take off 5 meters then 2 meters left move", english("टेकऑफ 5 मीटर, फिर 2 मीटर बाएं जाओ").replace(",", ""))
    }

    @Test fun teluguNumbersDirectionsVerbsAndConnectives() {
        assertEquals("3 meters forward move then land", english("మూడు మీటర్లు ముందుకు వెళ్ళు తర్వాత దిగు"))
        assertEquals("2 meters right move", english("రెండు మీటర్లు కుడి వైపుకు వెళ్ళు"))
        assertEquals("2.5 meters up move", english("రెండున్నర మీటర్లు పైకి వెళ్ళు"))
    }

    @Test fun tamilKannadaAndMalayalam() {
        assertEquals("3 meters forward move then land", english("மூன்று மீட்டர் முன்னால் செல் பிறகு தரையிறங்கு"))
        assertEquals("3 meters forward move then land", english("ಮೂರು ಮೀಟರ್ ಮುಂದೆ ಹೋಗು ನಂತರ ಇಳಿ"))
        assertEquals("3 meters forward move then land", english("മൂന്ന് മീറ്റർ മുന്നോട്ട് പോകൂ പിന്നെ ഇറങ്ങൂ"))
        // Loanwords become their native form first (டேக் ஆஃப் → take off), and status its Latin spelling.
        assertEquals("4 meters take off", english("நான்கு மீட்டர் டேக் ஆஃப்"))
        assertEquals("status", english("ஸ்டேட்டஸ்"))
    }

    @Test fun nativeDigitsBecomeAsciiAndGluedUnitsSplit() {
        assertEquals("3 meters forward", english("३ मीटर आगे"))
        assertEquals("2 meters down move", english("౨మీటర్లు కిందికి వెళ్ళు"))
    }

    @Test fun englishAndMixedTextKeepTheirLatinWords() {
        assertEquals("go forward a little bit then land", english("go forward a little bit then land"))
        assertEquals("take off to 3 m then forward 2 meters", english("take off to 3 m फिर आगे 2 मीटर"))
        assertEquals("", english("   "))
    }

    @Test fun negationsAndMinusWordsAreGlossedNeverDropped() {
        assertEquals("forward not move", english("आगे मत जाओ"))
        assertEquals("minus 2 meters", english("माइनस दो मीटर"))
    }

    @Test fun wordsNoTableReadsAreReportedNotGuessed() {
        // Tamil "பண்ணாதே" is "don't": no table has it, so the gloss cannot be trusted with the command.
        val tamil = Gloss.english("மூன்று மீட்டர் டேக் ஆஃப் பண்ணாதே")
        assertEquals(listOf("பண்ணாதே"), tamil.unknown)
        assertFalse(tamil.complete)
        // A bare half without a metre word and a scale word stay unread too.
        assertEquals(listOf("அரை"), Gloss.english("அரை முன்னால்").unknown)
        assertEquals(listOf("ஆயிரம்"), Gloss.english("ஆயிரம் மீட்டர்").unknown)
    }

    @Test fun punctuationIsKeptAndTheDandaIsAFullStop() {
        assertEquals("land.", english("उतरो।"))
        assertEquals("3 meters up move, then land", english("तीन मीटर ऊपर जाओ, फिर उतरो"))
    }
}
