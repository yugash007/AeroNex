package com.aeronex.app

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VerifiedModelStoreTest {
    private val payload = "small verified model".toByteArray()
    private val profile = LocalModelProfile("test.bin", payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })

    private fun withStore(test: (File, VerifiedModelStore) -> Unit) {
        val directory = Files.createTempDirectory("aeronex-model-test").toFile()
        try { test(directory, VerifiedModelStore(directory)) }
        finally { directory.deleteRecursively() }
    }

    @Test fun matchingFileIsInstalledAfterVerification() = withStore { dir, store ->
        store.install(profile, payload.inputStream(), { false })
        assertArrayEquals(payload, File(dir, profile.file).readBytes())
        assertEquals(listOf(profile.file), dir.list()!!.toList())
    }

    @Test fun corruptOrIncompleteFilesPreserveExistingModel() = withStore { dir, store ->
        val target = File(dir, profile.file).also { it.writeText("previous model") }
        for (bad in listOf(ByteArray(payload.size), payload.copyOf(payload.size - 1), payload + byteArrayOf(0))) {
            assertThrows(IllegalStateException::class.java) { store.install(profile, bad.inputStream(), { false }) }
            assertEquals("previous model", target.readText())
            assertEquals(listOf(profile.file), dir.list()!!.toList())
        }
    }

    @Test fun cancelledTransfersDoNotPublishPartialFiles() = withStore { dir, store ->
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            store.install(profile, payload.inputStream(), { cancelled }, { cancelled = true })
        }
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test fun transferFilesLeftByATerminatedProcessAreRemovedBeforeTheNextInstall() = withStore { dir, store ->
        File(dir, "model-123.part").writeText("partial download")
        File(dir, "model-stage-456/vosk-model/am").mkdirs()
        File(dir, "model-stage-456/vosk-model/am/final.mdl").writeText("partial extraction")
        File(dir, "model-notes.txt").writeText("not a transfer file")
        store.install(profile, payload.inputStream(), { false })
        assertEquals(listOf("model-notes.txt", profile.file), dir.list()!!.sorted())
    }

    @Test fun aSweepNeverRemovesTheTemporaryFileOfALiveTransfer() = withStore { dir, store ->
        val lock = LocalModelDirectoryLocks.forDirectory(dir)
        val failure = AtomicReference<Throwable?>()
        val readLease = lock.readLock()
        readLease.lock()
        val transfer = Thread {
            try { VerifiedModelStore(dir).install(profile, payload.inputStream(), { false }) }
            catch (error: Throwable) { failure.set(error) }
        }.also { it.isDaemon = true }
        try {
            transfer.start()
            // The verified transfer has closed its temporary file and waits to publish it.
            awaitQueued(lock, transfer)
            assertThrows(IllegalStateException::class.java) {
                store.install(profile, ByteArray(payload.size).inputStream(), { false })
            }
        } finally { readLease.unlock() }
        transfer.join(5000)
        assertNull(failure.get())
        assertArrayEquals(payload, File(dir, profile.file).readBytes())
        assertEquals(listOf(profile.file), dir.list()!!.toList())
    }

    @Test fun aProfileWithoutASourceIsImportOnly() {
        // The whisper.cpp/LFM download fallback went with those engines (T13): no source is ever guessed.
        val failure = assertThrows(IllegalStateException::class.java) {
            LocalModelProfile("ggml-tiny-q5_1.bin", 1, "unused").url
        }
        assertTrue(failure.message!!.contains("import"))
        assertEquals("https://example.org/a.bin", LocalModelProfile("a.bin", 1, "unused", "https://example.org/a.bin").url)
    }

    private fun awaitQueued(lock: java.util.concurrent.locks.ReentrantReadWriteLock, thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!lock.hasQueuedThread(thread) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Thread must reach the directory lock queue", lock.hasQueuedThread(thread))
    }
}
