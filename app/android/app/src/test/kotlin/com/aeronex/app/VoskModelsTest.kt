package com.aeronex.app

import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class VoskModelsTest {
    @Test fun languageAliasesSelectTheirCorrespondingOfficialModelOnly() {
        val expected = mapOf("en" to "vosk-en-in", "en-IN" to "vosk-en-in", "en-US" to "vosk-en-in",
            "EN-in" to "vosk-en-in", "te" to "vosk-te", "te-IN" to "vosk-te",
            "hi" to "vosk-hi", "hi-IN" to "vosk-hi", "ta" to null, "kn-IN" to null,
            "ml" to null, "auto" to null, "en-GB" to null, "" to null)
        expected.forEach { (language, profile) -> assertEquals(language, profile, VoskModels.profileForLanguage(language)) }
    }

    @Test fun supportedProfilesContainVerifiedIndianEnglishTeluguAndHindiArchives() {
        for (id in listOf("vosk-en-in", "vosk-te", "vosk-hi")) {
            val profile = VoskModels.profiles[id]
            assertNotNull("Missing supported Vosk profile $id", profile)
            assertTrue(profile!!.url.startsWith("https://alphacephei.com/vosk/models/"))
            assertTrue(profile.archiveFiles.containsKey("am/final.mdl"))
            assertTrue(profile.archiveFiles.containsKey("graph/HCLr.fst"))
            assertTrue(profile.archiveFiles.containsKey("graph/Gr.fst"))
        }
    }

    @Test fun downloadedOfficialDirectoriesPassAndroidInventoryWithoutPreferences() {
        val source = System.getenv("AERONEX_VOSK_MODEL_DIR")
        assumeTrue("Set AERONEX_VOSK_MODEL_DIR to check downloaded models", !source.isNullOrBlank())
        val profiles = VoskModels.profiles
        assertEquals(3, profiles.size)
        val inventory = LocalModelInventory(File(source!!), profiles)
        val start = System.nanoTime()
        val states = inventory.states()
        assertTrue("Real manifests must all verify: $states", states.values.all { it == "ready" })
        val coldMs = (System.nanoTime() - start) / 1_000_000
        val cached = System.nanoTime()
        assertEquals(states, inventory.states())
        println("Vosk manifest inventory cold=${coldMs}ms cached=${(System.nanoTime() - cached) / 1_000_000}ms")
    }
}
