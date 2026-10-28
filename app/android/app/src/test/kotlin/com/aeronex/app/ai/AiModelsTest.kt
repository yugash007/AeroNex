package com.aeronex.app.ai

import com.aeronex.app.LocalModelDirectoryLocks
import com.aeronex.app.LocalModelProfile
import com.aeronex.app.VoskModels
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    private val directory: File = Files.createTempDirectory("ai-models").toFile()

    @After fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test fun theProfilesArePinnedBySizeAndHash() {
        val lfm = AiModels.profiles.getValue(AiModels.LFM)
        assertEquals(LocalModelProfile("LFM2.5-VL-450M-Q4_K_M.gguf", 229_313_568L,
            "1093f1331319199bbcacdbd7ecc9aa6e5678db6b55073948d0f508be90c8ab68",
            "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/LFM2.5-VL-450M-Q4_K_M.gguf"), lfm)
        val needle = AiModels.profiles.getValue(AiModels.NEEDLE)
        assertEquals(LocalModelProfile("needle3.cact", 35_335_380L,
            "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38",
            "https://huggingface.co/Cactus-Compute/needle3/resolve/2ae11323dc000f5e70c49f7403efa6af12ba9e67/needle3.cact"),
            needle)
        assertEquals(setOf("lfm-q4", "needle3"), AiModels.profiles.keys)
        assertTrue(AiModels.profiles.values.all { it.url.startsWith("https://") })
    }

    @Test fun theAiModelsHaveTheirOwnDirectoryBesideVosk() {
        assertTrue(AiModels.profiles.keys.none { it in VoskModels.profiles })
        val voskFiles = VoskModels.profiles.values.flatMap { listOf(it.file, it.runtimeFile) }.toSet()
        assertTrue(AiModels.profiles.values.none { it.file in voskFiles })
        assertEquals(File("/data/user/0/app/files", "ai-models"), AiModels.directory(File("/data/user/0/app/files")))
    }

    // N6 (emulator): a Vosk session holds the read lease of filesDir/models for its whole life (VoskNativeModel), so
    // an AI model sharing that directory stayed at "Importing… 100%" and Remove would block the channel thread.
    @Test fun anOpenVoskModelNeverHoldsUpAnAiModelInstallOrRemove() {
        val files = File(directory, "files")
        val voskDirectory = File(files, "models").also { it.mkdirs() }
        val leased = CountDownLatch(1)
        val release = CountDownLatch(1)
        val vosk = thread(name = "speech") {
            val lease = LocalModelDirectoryLocks.forDirectory(voskDirectory).readLock()
            lease.lock()
            try {
                leased.countDown()
                release.await()
            } finally {
                lease.unlock()
            }
        }
        assertTrue(leased.await(5, TimeUnit.SECONDS))
        val bytes = "needle".toByteArray()
        val store = AiModelStore(AiModels.directory(files).also { it.mkdirs() },
            mapOf(AiModels.LFM to profile("a.gguf", "x".toByteArray()), AiModels.NEEDLE to profile("b.cact", bytes)))
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit { store.install(AiModels.NEEDLE, bytes.inputStream(), { false }) }.get(5, TimeUnit.SECONDS)
            assertEquals("ready", store.states()[AiModels.NEEDLE])
            assertTrue(worker.submit<Boolean> { store.remove(AiModels.NEEDLE) }.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            vosk.join()
            worker.shutdownNow()
        }
    }

    @Test fun aiModelsLeftInTheVoskDirectoryMoveToTheAiDirectory() {
        val files = File(directory, "files")
        val legacy = File(files, "models").also { it.mkdirs() }
        val lfm = AiModels.profiles.getValue(AiModels.LFM).file
        val needle = AiModels.profiles.getValue(AiModels.NEEDLE).file
        File(legacy, lfm).writeText("0.2.0 download")
        File(legacy, needle).writeText("old needle")
        File(AiModels.directory(files).also { it.mkdirs() }, needle).writeText("new needle")

        AiModels.adoptLegacy(files)

        assertEquals("0.2.0 download", File(AiModels.directory(files), lfm).readText())
        assertFalse(File(legacy, lfm).exists())
        // A model already in the AI directory is never replaced (the store verifies whatever is there).
        assertEquals("new needle", File(AiModels.directory(files), needle).readText())
        assertTrue(File(legacy, needle).exists())
    }

    @Test fun filesAreReadyOnlyAfterVerifiedInstallsOfBothModels() {
        val lfmBytes = "lfm weights".toByteArray()
        val needleBytes = "needle weights".toByteArray()
        val store = AiModelStore(directory, mapOf(AiModels.LFM to profile("a.gguf", lfmBytes),
            AiModels.NEEDLE to profile("b.cact", needleBytes)))
        assertEquals(mapOf(AiModels.LFM to "missing", AiModels.NEEDLE to "missing"), store.states())
        assertNull(store.readyFiles())
        store.install(AiModels.LFM, lfmBytes.inputStream(), cancelled = { false })
        assertFalse(store.ready())
        store.install(AiModels.NEEDLE, needleBytes.inputStream(), cancelled = { false })
        assertTrue(store.ready())
        assertEquals(AiModelFiles(File(directory, "a.gguf"), File(directory, "b.cact")), store.readyFiles())
    }

    @Test fun aWrongFileIsNeverInstalled() {
        val store = AiModelStore(directory, mapOf(AiModels.LFM to profile("a.gguf", "good".toByteArray()),
            AiModels.NEEDLE to profile("b.cact", "x".toByteArray())))
        val failure = runCatching { store.install(AiModels.LFM, "evil".toByteArray().inputStream(), { false }) }
        assertTrue(failure.isFailure)
        assertFalse(File(directory, "a.gguf").exists())
    }

    @Test fun removeDeletesOneModel() {
        val bytes = "needle".toByteArray()
        val store = AiModelStore(directory, mapOf(AiModels.LFM to profile("a.gguf", "x".toByteArray()),
            AiModels.NEEDLE to profile("b.cact", bytes)))
        store.install(AiModels.NEEDLE, bytes.inputStream(), { false })
        assertEquals("ready", store.states()[AiModels.NEEDLE])
        assertTrue(store.remove(AiModels.NEEDLE))
        assertEquals("missing", store.states()[AiModels.NEEDLE])
        assertFalse(store.remove(AiModels.NEEDLE))
    }

    private fun profile(name: String, bytes: ByteArray) = LocalModelProfile(name, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
        "https://example.invalid/$name")
}
