package com.aeronex.app.ai

import android.os.Build
import android.os.Process
import com.aeronex.app.speech.DeviceTier

/** The process facts AI availability depends on. */
data class AiDevice(
    /** `Process.is64Bit()`: false when the 32-bit (armeabi-v7a) APK runs, even on a 64-bit phone. */
    val processIs64Bit: Boolean,
    /** `Build.SUPPORTED_64_BIT_ABIS`, preferred first: empty on a 32-bit-only phone. */
    val supported64BitAbis: List<String>,
) {
    companion object {
        fun current() = AiDevice(Process.is64Bit(), Build.SUPPORTED_64_BIT_ABIS.toList())
    }
}

/**
 * Spec A6: the AI path exists only in the arm64-v8a APK (Cactus ships android-arm64 only), on Tier M/H by default
 * (EnginePolicy.tier), with both models verified. Otherwise the phone stays rules-only and [reason] says why in plain
 * words. Reasons come in that order (ABI, memory, models), and the native library is loaded last.
 */
data class AiAvailability(val available: Boolean, val reason: String?) {
    companion object {
        const val NEEDS_64_BIT_PHONE = "Needs a 64-bit phone"
        const val NEEDS_64_BIT_APP = "Needs the 64-bit (arm64) app"
        const val NEEDS_ARM64 = "Needs a 64-bit ARM phone"
        const val NOT_ENOUGH_MEMORY = "Not enough memory on this phone"
        const val DOWNLOAD_MODELS = "Download the AI models in Models"
        const val NATIVE_FAILED = "AI could not start on this phone"

        private val AVAILABLE = AiAvailability(true, null)

        /** [allowTierL]: an explicit opt-in for Tier L (off by default). [nativeLoads] loads libaeronex_ai.so. */
        fun check(
            device: AiDevice,
            tier: DeviceTier,
            modelsReady: Boolean,
            allowTierL: Boolean = false,
            nativeLoads: () -> Boolean = { NativeAiEngine.load() != null },
        ): AiAvailability {
            val reason = when {
                device.supported64BitAbis.isEmpty() -> NEEDS_64_BIT_PHONE
                device.supported64BitAbis.first() != ARM64 -> NEEDS_ARM64
                !device.processIs64Bit -> NEEDS_64_BIT_APP
                tier == DeviceTier.L && !allowTierL -> NOT_ENOUGH_MEMORY
                !modelsReady -> DOWNLOAD_MODELS
                !nativeLoads() -> NATIVE_FAILED
                else -> return AVAILABLE
            }
            return AiAvailability(false, reason)
        }

        /** Planner decode threads: the cores, at most 4 (llama.cpp gains little beyond the big cores). */
        fun plannerThreads(cpuCores: Int): Int = cpuCores.coerceIn(1, 4)

        private const val ARM64 = "arm64-v8a"
    }
}
