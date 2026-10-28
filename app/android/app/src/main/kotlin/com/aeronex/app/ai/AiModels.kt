package com.aeronex.app.ai

import com.aeronex.app.LocalModelDirectoryLocks
import com.aeronex.app.LocalModelInventory
import com.aeronex.app.LocalModelProfile
import com.aeronex.app.VerifiedModelStore
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The two AI models (not bundled in the APK): the LFM2.5 planner and the Cactus Needle tool caller, pinned by size
 * and SHA-256 (the v0.2.0 profiles). They live in their own `filesDir/ai-models` store: a Vosk session holds the read
 * lease of `filesDir/models` for its whole life (VoskNativeModel), which would hold up every AI install and removal.
 */
object AiModels {
    const val LFM = "lfm-q4"
    const val NEEDLE = "needle3"

    val profiles: Map<String, LocalModelProfile> = mapOf(
        LFM to LocalModelProfile(
            "LFM2.5-VL-450M-Q4_K_M.gguf", 229_313_568L,
            "1093f1331319199bbcacdbd7ecc9aa6e5678db6b55073948d0f508be90c8ab68",
            "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/LFM2.5-VL-450M-Q4_K_M.gguf",
        ),
        NEEDLE to LocalModelProfile(
            "needle3.cact", 35_335_380L,
            "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38",
            "https://huggingface.co/Cactus-Compute/needle3/resolve/2ae11323dc000f5e70c49f7403efa6af12ba9e67/needle3.cact",
        ),
    )

    /** The AI models' verified store, beside the Vosk store `filesDir/models` (its own directory lock). */
    fun directory(filesDir: File) = File(filesDir, "ai-models")

    /**
     * Moves AI model files an earlier version (0.2.0) left in `filesDir/models` into [directory], unless one is there
     * already; the store verifies them before use. Best effort: a file that cannot move stays where it is.
     */
    fun adoptLegacy(filesDir: File) {
        val target = directory(filesDir)
        for (profile in profiles.values) {
            val legacy = File(File(filesDir, "models"), profile.file)
            val adopted = File(target, profile.file)
            if (!legacy.isFile || adopted.exists() || !(target.isDirectory || target.mkdirs())) continue
            try {
                Files.move(legacy.toPath(), adopted.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: IOException) {
            } catch (_: SecurityException) {
            }
        }
    }
}

/** The verified model files the runtime opens. */
data class AiModelFiles(val lfm: File, val needle: File)

/**
 * Status, download, import and removal of the AI models through [VerifiedModelStore] (a file is visible only after
 * its size and hash match). Blocking: call from a worker thread. Unload the planner before [remove] (the Needle
 * mapping lives until the process ends; removing its file is still safe on Android).
 */
class AiModelStore(
    val directory: File,
    private val profiles: Map<String, LocalModelProfile> = AiModels.profiles,
) {
    private val inventory = LocalModelInventory(directory, profiles)

    /** Per model id: "ready", "missing", "invalid", "unreadable" or "checking". */
    fun states(): Map<String, String> = inventory.states()

    fun ready(): Boolean = states().let { states -> profiles.keys.all { states[it] == "ready" } }

    /** Both files, or null unless both are verified. */
    fun readyFiles(): AiModelFiles? = if (ready()) AiModelFiles(file(AiModels.LFM), file(AiModels.NEEDLE)) else null

    fun download(id: String, cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        try { VerifiedModelStore(directory).download(profiles.getValue(id), cancelled, progress) }
        finally { inventory.invalidate(id) }
    }

    fun install(id: String, source: InputStream, cancelled: () -> Boolean, progress: (Long) -> Unit = {}) {
        try { VerifiedModelStore(directory).install(profiles.getValue(id), source, cancelled, progress) }
        finally { inventory.invalidate(id) }
    }

    /** Deletes one model file; returns whether it existed. */
    fun remove(id: String): Boolean {
        val lock = LocalModelDirectoryLocks.forDirectory(directory).writeLock()
        lock.lock()
        try {
            return Files.deleteIfExists(file(id).toPath())
        } finally {
            lock.unlock()
            inventory.invalidate(id)
        }
    }

    private fun file(id: String) = File(directory, profiles.getValue(id).file)
}
