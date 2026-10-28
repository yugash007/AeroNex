package com.aeronex.app.speech

import com.aeronex.app.VoskModels
import com.aeronex.app.grammar.GrammarTables
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the real T9 assets the APK ships. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GrammarAssetsTest {
    private val assets = GrammarAssets.load { File("src/main/assets/$it").inputStream() }

    @Test fun everyGrammarLanguageIsDescribed() {
        assertEquals(GrammarTables.LANGUAGES.toSet(), assets.languages.keys)
        for (code in GrammarTables.LANGUAGES) {
            val language = assets.language(code)
            assertEquals("kws keywords are 'stop' only (T9)", listOf("stop"), language.kwsKeywords)
            assertEquals(GrammarTables.lexicon(code).stopLane, language.stopLane)
        }
        assertThrows(IllegalArgumentException::class.java) { assets.language("fr") }
    }

    @Test fun stopTokensAreTheFirstWordsOfTheStopLane() {
        assertEquals(setOf("stop"), assets.language("en").stopTokens)
        assertEquals(setOf("रुको", "रुक", "स्टॉप"), assets.language("hi").stopTokens)
        assertEquals(setOf("ఆపు", "ఆగు", "స్టాప్"), assets.language("te").stopTokens)
        assertTrue("hold is never a stop token", assets.languages.values.none { "hold" in it.stopTokens })
    }

    @Test fun voskLanguagesNameTheirModelAndBothGrammars() {
        assertEquals(GrammarTables.VOSK_LANGUAGES.toSet(), assets.languages.values.filter { it.vosk != null }.map { it.code }.toSet())
        for (code in GrammarTables.VOSK_LANGUAGES) {
            val vosk = assets.language(code).vosk!!
            val profile = VoskModels.profiles.getValue(VoskModels.profileForLanguage(code)!!)
            assertEquals(profile.runtimeFile, vosk.model)
            val command = JSONArray(assets.voskGrammar(code))
            val stop = JSONArray(assets.voskStopGrammar(code))
            val stopPhrases = (0 until stop.length()).map { stop.getString(it) }.toSet()
            assertTrue(assets.language(code).stopLane.all { it in stopPhrases })
            assertTrue("[unk]" in stopPhrases)
            assertTrue(command.length() > stop.length())
        }
        assertNull(assets.language("ta").vosk)
        assertThrows(IllegalStateException::class.java) { assets.voskGrammar("ta") }
    }

    @Test fun aGrammarThatDoesNotMatchItsHashIsRefused() {
        val tampered = GrammarAssets.load { path ->
            val bytes = File("src/main/assets/$path").readBytes()
            (if (path == "grammar/stop_en.json") bytes + ' '.code.toByte() else bytes).inputStream()
        }
        assertThrows(IllegalStateException::class.java) { tampered.voskStopGrammar("en") }
        assertTrue(tampered.voskGrammar("en").isNotEmpty())
    }

    @Test fun kwsLinesComeFromTheManifestPerSpotterModel() {
        assertEquals(listOf("▁ST O P @stop"), assets.kwsLines("kws-gigaspeech-3.3M-chunk-16"))
        assertEquals(listOf("S T AA1 P @stop"), assets.kwsLines("kws-zh-en-3M-chunk-8"))
        assertThrows(IllegalArgumentException::class.java) { assets.kwsLines("kws-unknown") }
    }
}
