package com.aeronex.app.bench

/**
 * One memory reading of the bench process (spec 6.2 metrics), in KiB: `Debug.MemoryInfo` total Private Dirty and
 * `summary.total-swap` (SwapPss where the kernel reports it), TOTAL PSS, the other `summary.*` stats, and
 * `/proc/self/smaps_rollup` when readable ([fileBackedKb] estimates the file-backed resident pages).
 */
data class MemorySample(
    val label: String,
    val privateDirtyKb: Long,
    val swapPssKb: Long?,
    val totalPssKb: Long,
    val stats: Map<String, Long>,
    val rollupKb: Map<String, Long>?,
) {
    /** The spec 6.2 hard-cap metric. */
    val privateDirtyPlusSwapKb: Long get() = privateDirtyKb + (swapPssKb ?: 0)

    /** The spec 6.2 soft-cap metric, estimated from smaps_rollup. */
    val fileBackedKb: Long? get() = SmapsRollup.fileBackedKb(rollupKb)
}

object SmapsRollup {
    const val PATH = "/proc/self/smaps_rollup"
    private val FIELD = Regex("^([A-Za-z_]+):\\s+(\\d+) kB$")

    /** The `Name: <n> kB` lines of smaps_rollup; the header line is skipped. */
    fun parse(text: String): Map<String, Long> = text.lineSequence()
        .mapNotNull { FIELD.matchEntire(it.trim())?.destructured }
        .associate { (name, kb) -> name to kb.toLong() }

    /** Resident pages that are not anonymous (mapped files, plus shmem): `Rss - Anonymous`. */
    fun fileBackedKb(rollup: Map<String, Long>?): Long? {
        val rss = rollup?.get("Rss") ?: return null
        val anonymous = rollup["Anonymous"] ?: return null
        return rss - anonymous
    }
}
