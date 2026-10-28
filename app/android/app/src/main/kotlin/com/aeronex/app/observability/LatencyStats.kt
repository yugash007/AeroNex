package com.aeronex.app.observability

import kotlin.math.ceil

/** Rolling latency figures for Diagnostics (spec 5): nearest-rank percentiles in ms. */
data class LatencySummary(val count: Int, val p50Ms: Double, val p95Ms: Double, val maxMs: Double) {
    override fun toString() = "n=$count p50=$p50Ms ms p95=$p95Ms ms max=$maxMs ms"
}

object LatencyStats {
    fun summarize(valuesMs: Collection<Double>): LatencySummary? {
        if (valuesMs.isEmpty()) return null
        val sorted = valuesMs.sorted()
        return LatencySummary(sorted.size, percentile(sorted, 50.0), percentile(sorted, 95.0), sorted.last())
    }

    /** Nearest-rank percentile [p] (0–100] of [values]. */
    fun percentile(values: List<Double>, p: Double): Double {
        require(values.isNotEmpty() && p > 0 && p <= 100) { "Need values and 0 < p <= 100" }
        val sorted = values.sorted()
        return sorted[ceil(p / 100 * sorted.size).toInt().coerceIn(1, sorted.size) - 1]
    }
}
