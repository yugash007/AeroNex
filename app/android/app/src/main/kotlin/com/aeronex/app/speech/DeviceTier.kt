package com.aeronex.app.speech

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Spec 6: L is the floor (2-3 GB or 32-bit-only), M standard (4-6 GB arm64), H plus (6 GB and more). */
enum class DeviceTier {
    L, M, H;

    fun downgrade(): DeviceTier = entries[maxOf(0, ordinal - 1)]
}

/** The device facts [EnginePolicy] classifies (spec 6.1). */
data class DeviceFacts(
    /** `Build.SUPPORTED_64_BIT_ABIS`: empty on a 32-bit-only userspace. */
    val supported64BitAbis: List<String>,
    /** `ActivityManager.MemoryInfo.totalMem`. */
    val totalMemBytes: Long,
    /** `ActivityManager.isLowRamDevice()`, the Go flag. */
    val lowRamDevice: Boolean,
    val cpuCores: Int,
) {
    companion object {
        fun read(context: Context): DeviceFacts {
            val activities = context.getSystemService(ActivityManager::class.java)
            val memory = ActivityManager.MemoryInfo().also { activities.getMemoryInfo(it) }
            return DeviceFacts(Build.SUPPORTED_64_BIT_ABIS.toList(), memory.totalMem, activities.isLowRamDevice,
                Runtime.getRuntime().availableProcessors())
        }
    }
}

/**
 * The persisted result of the first-session micro-benchmark (spec 6.1; measured by the T14 harness / T12 wiring).
 * [missedTierTargets] downgrades one tier. On Tier L the CTC path opens only when [ctcRtf] is at most 0.15 **and** the
 * memory cap held ([ctcWithinMemoryCap]): spec 6.3 gates on both, and W1 measured about 300 MiB of heap per
 * IndicConformer language. [tunedAsrThreads] is the Tier L thread count tuned by measured RTF (2-4).
 */
data class FirstSessionBenchmark(
    val missedTierTargets: Boolean = false,
    val ctcRtf: Double? = null,
    val ctcWithinMemoryCap: Boolean = false,
    val tunedAsrThreads: Int? = null,
)
