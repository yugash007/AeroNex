package com.aeronex.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchPlanTest {
    @Test fun theDefaultsRunEveryKindInEveryLanguageWithSevenClips() {
        val request = BenchRequest.parse(engines = null, languages = null, clips = null, threads = null)
        assertEquals(UnitKind.entries.toSet(), request.kinds)
        assertEquals(listOf("en", "hi", "te", "ta", "kn", "ml"), request.languages)
        assertEquals(7, request.clipsPerLanguage)
        assertNull(request.threads)
    }

    @Test fun parsesTheAmStartExtras() {
        val request = BenchRequest.parse(" vosk, kws ", "en ,hi", 3, 2)
        assertEquals(setOf(UnitKind.VOSK, UnitKind.KWS), request.kinds)
        assertEquals(listOf("en", "hi"), request.languages)
        assertEquals(3, request.clipsPerLanguage)
        assertEquals(2, request.threads)
        assertEquals("blank extras are the defaults", BenchRequest.parse("", " ", null, null), BenchRequest.parse(null, null, null, null))
    }

    @Test fun refusesUnknownEnginesLanguagesAndCounts() {
        assertThrows(IllegalArgumentException::class.java) { BenchRequest.parse("whisper", null, null, null) }
        assertThrows(IllegalArgumentException::class.java) { BenchRequest.parse(null, "fr", null, null) }
        assertThrows(IllegalArgumentException::class.java) { BenchRequest.parse(null, null, 0, null) }
        assertThrows(IllegalArgumentException::class.java) { BenchRequest.parse(null, null, null, 0) }
    }

    private val catalogue = Catalogue(
        ctcPacks = mapOf("ta" to "indicconformer-ta-ctc-int8", "te" to "indicconformer-te-ctc-int8"),
        kwsPacks = listOf("kws-gigaspeech-3.3M-chunk-16" to "en"),
    )

    @Test fun unitsAreTheInstalledEnginesInKindThenLanguageOrder() {
        val installed = Installed(voskLanguages = setOf("en", "te"),
            packs = setOf("indicconformer-ta-ctc-int8", "kws-gigaspeech-3.3M-chunk-16"))
        val plan = BenchPlan.of(BenchRequest.parse(null, null, null, null), catalogue, installed)
        assertEquals(listOf("vosk-en", "vosk-te", "ctc-ta", "kws-gigaspeech-3.3M-chunk-16"), plan.units.map { it.id })
        assertEquals(BenchUnit("ctc-ta", UnitKind.CTC, "ta", "indicconformer-ta-ctc-int8"), plan.units[2])
        assertEquals(BenchUnit("kws-gigaspeech-3.3M-chunk-16", UnitKind.KWS, "en", "kws-gigaspeech-3.3M-chunk-16"),
            plan.units[3])
        assertEquals(mapOf("vosk-hi" to BenchPlan.NOT_INSTALLED, "ctc-te" to BenchPlan.NOT_INSTALLED), plan.skipped)
    }

    @Test fun theKeywordSpotterRunsOnlyOnItsOwnLanguagesClips() {
        val installed = Installed(voskLanguages = emptySet(), packs = setOf("kws-gigaspeech-3.3M-chunk-16"))
        val plan = BenchPlan.of(BenchRequest.parse("kws", "ta", null, null), catalogue, installed)
        assertEquals(emptyList<BenchUnit>(), plan.units)
        assertEquals(emptyMap<String, String>(), plan.skipped)
        val english = BenchPlan.of(BenchRequest.parse("kws", "en", null, null), catalogue, installed)
        assertEquals(listOf("kws-gigaspeech-3.3M-chunk-16"), english.units.map { it.id })
    }
}
