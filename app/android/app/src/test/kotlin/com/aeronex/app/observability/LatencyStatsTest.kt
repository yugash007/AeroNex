package com.aeronex.app.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyStatsTest {
    @Test fun nearestRankPercentiles() {
        val values = (1..10).map { it.toDouble() }.shuffled()
        val summary = LatencyStats.summarize(values)!!
        assertEquals(10, summary.count)
        assertEquals(5.0, summary.p50Ms, 0.0)
        assertEquals(10.0, summary.p95Ms, 0.0)
        assertEquals(10.0, summary.maxMs, 0.0)
        assertEquals(1.0, LatencyStats.percentile(listOf(1.0, 2.0), 50.0), 0.0)
        assertEquals(2.0, LatencyStats.percentile(listOf(1.0, 2.0), 51.0), 0.0)
        assertEquals(19.0, LatencyStats.percentile((1..20).map { it.toDouble() }, 95.0), 0.0)
    }

    @Test fun emptyHasNoSummary() {
        assertNull(LatencyStats.summarize(emptyList()))
    }

    @Test fun summaryPrintsCompactly() {
        assertEquals("n=2 p50=1.0 ms p95=2.5 ms max=2.5 ms", LatencyStats.summarize(listOf(2.5, 1.0)).toString())
    }
}
