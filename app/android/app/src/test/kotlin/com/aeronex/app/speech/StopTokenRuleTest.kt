package com.aeronex.app.speech

import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StopTokenRuleTest {
    private val en = StopTokenRule(setOf("stop"))

    @Test fun tokensAreTheFirstWordOfEachStopLanePhrase() {
        assertEquals(setOf("stop"), StopTokenRule.tokensFor(listOf("stop")))
        assertEquals(setOf("रुको", "रुक", "स्टॉप"), StopTokenRule.tokensFor(listOf("रुको", "रुक जाओ", "स्टॉप")))
        assertEquals(setOf("ఆపు", "ఆగు", "స్టాప్"), StopTokenRule.tokensFor(listOf("ఆపు", "ఆగు", "స్టాప్")))
        assertThrows(IllegalArgumentException::class.java) { StopTokenRule(emptySet()) }
    }

    @Test fun firesOnTheSecondConsecutiveStepOnly() {
        assertFalse(en.partial(listOf("stop")))
        assertTrue(en.partial(listOf("stop")))
        assertFalse("fires once until reset", en.partial(listOf("stop")))
        en.reset()
        assertFalse(en.partial(listOf("go", "stop")))
        assertTrue(en.partial(listOf("go", "stop")))
    }

    @Test fun aStepWithoutATokenRestartsTheCount() {
        val steps = listOf(listOf("stop"), listOf("go"), listOf("stop"), emptyList(), listOf("stop"), listOf("move"))
        assertTrue(steps.none { en.partial(it) })
    }

    @Test fun holdAndHoverAreNeverStopTokens() {
        val steps = listOf(listOf("hold"), listOf("hold"), listOf("hover"), listOf("hover"), listOf("hold", "position"))
        assertTrue(steps.none { en.partial(it) })
        assertFalse(en.final(listOf("hold")))
        assertFalse(en.final(listOf("hover")))
    }

    @Test fun aFinalResultWithATokenFiresAtOnce() {
        assertTrue(en.final(listOf("stop")))
        assertFalse(en.final(listOf("stop")))
        en.reset()
        assertFalse(en.final(listOf("take", "off")))
        assertFalse(en.partial(listOf("stop")))
        assertTrue(en.partial(listOf("stop")))
    }

    @Test fun matchingIsNfcAndCaseInsensitive() {
        val hi = StopTokenRule(StopTokenRule.tokensFor(listOf("रुको", "रुक जाओ", "स्टॉप")))
        val decomposed = Normalizer.normalize("रुक", Normalizer.Form.NFD)
        assertFalse(hi.partial(listOf(decomposed)))
        assertTrue(hi.partial(listOf("रुक", "जाओ")))
        assertTrue(StopTokenRule(setOf("stop")).let { it.partial(listOf("STOP")); it.partial(listOf("Stop")) })
    }
}
