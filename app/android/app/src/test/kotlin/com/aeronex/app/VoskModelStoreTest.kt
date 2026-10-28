package com.aeronex.app

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VoskModelStoreTest {
    private val modelFiles = mapOf("am/final.mdl" to "model".toByteArray(),
        "conf/model.conf" to "configuration".toByteArray(), "ivector/online_cmvn.conf" to byteArrayOf())
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun profile(bytes: ByteArray, files: Map<String, ByteArray> = modelFiles) =
        LocalModelProfile("vosk-test.zip", bytes.size.toLong(), sha(bytes), archiveFiles =
            files.mapValues { (_, data) -> LocalModelFile(data.size.toLong(), sha(data)) })

    private fun validZip() = zip(listOf("vosk-test/" to byteArrayOf(),
        "vosk-test/am/" to byteArrayOf()) + modelFiles.map { (name, data) -> "vosk-test/$name" to data })

    private fun withDirectory(test: (File, VerifiedModelStore) -> Unit) {
        val directory = Files.createTempDirectory("aeronex-vosk-test").toFile()
        try { test(directory, VerifiedModelStore(directory)) }
        finally { directory.deleteRecursively() }
    }

    @Test fun officialZipPublishesAnExtractedModelDirectory() {
        val directory = Files.createTempDirectory("aeronex-vosk-test").toFile()
        try {
            val bytes = validZip()
            val profile = profile(bytes)
            VerifiedModelStore(directory).install(profile, bytes.inputStream(), { false })
            assertTrue("Vosk needs an extracted directory", File(directory, "vosk-test").isDirectory)
            assertEquals("model", File(directory, "vosk-test/am/final.mdl").readText())
            assertFalse(File(directory, "vosk-test.zip").exists())
            assertEquals(listOf("vosk-test"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

    @Test fun inventoryUsesEveryExtractedFileWithoutPreferences() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        assertEquals("missing", inventory.states()["vosk-test"])
        store.install(profile, bytes.inputStream(), { false })
        assertEquals("ready", inventory.states()["vosk-test"])
        assertEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/ivector/online_cmvn.conf").delete()
        assertNotEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/ivector/online_cmvn.conf").writeBytes(byteArrayOf())
        assertEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/am/final.mdl").writeText("wrong")
        assertNotEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/am/final.mdl").writeText("model")
        assertEquals("ready", inventory.states()["vosk-test"])
    }

    @Test fun inventoryRejectsADirectoryInPlaceOfARequiredFile() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        assertEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/am/final.mdl").delete()
        File(dir, "vosk-test/am/final.mdl").mkdir()
        assertNotEquals("ready", inventory.states()["vosk-test"])
    }

    @Test fun malformedArchivesDoNotPublishOrReplaceAnExistingModel() = withDirectory { dir, store ->
        val good = validZip()
        val profile = profile(good)
        store.install(profile, good.inputStream(), { false })
        for (bad in listOf(good.copyOf(good.size - 1), ByteArray(good.size), good + byteArrayOf(0))) {
            assertThrows(IllegalStateException::class.java) {
                store.install(profile, bad.inputStream(), { false })
            }
            assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
            assertEquals(listOf("vosk-test"), dir.list()!!.toList())
        }
    }

    @Test fun cancelledReplacementRetainsPreviousCompleteModel() = withDirectory { dir, store ->
        val good = validZip()
        val profile = profile(good)
        store.install(profile, good.inputStream(), { false })
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            store.install(profile, good.inputStream(), { cancelled }, { cancelled = true })
        }
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun archiveExtractionRejectsTraversalAbsoluteAndWindowsPaths() = withDirectory { dir, store ->
        val existing = validZip()
        store.install(profile(existing), existing.inputStream(), { false })
        val paths = listOf("vosk-test/../outside", "/absolute", "C:/absolute",
            "vosk-test\\am\\final.mdl", "vosk-test/am/./final.mdl", "other/am/final.mdl")
        for (path in paths) {
            val bad = zip(modelFiles.map { (name, data) -> "vosk-test/$name" to data } +
                listOf(path to "bad".toByteArray()))
            assertThrows("Path $path must be rejected", IllegalStateException::class.java) {
                store.install(profile(bad), bad.inputStream(), { false })
            }
            assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
            assertEquals(listOf("vosk-test"), dir.list()!!.toList())
        }
    }

    @Test fun duplicateAndUnexpectedEntriesAreRejected() = withDirectory { dir, store ->
        val entries = modelFiles.map { (name, data) -> "vosk-test/$name" to data }
        val duplicate = zip(entries + listOf("vosk-test/am/final.mdX" to "model".toByteArray()))
            .let { bytes -> replaceAscii(bytes, "final.mdX", "final.mdl") }
        for (bad in listOf(duplicate, zip(entries + listOf("vosk-test/unknown" to byteArrayOf())),
            zip(entries + listOf("vosk-test/unknown/" to byteArrayOf())))) {
            assertThrows(IllegalStateException::class.java) { store.install(profile(bad), bad.inputStream(), { false }) }
            assertTrue(dir.list()!!.isEmpty())
        }
    }

    @Test fun missingOversizedAndWrongHashEntriesAreRejected() = withDirectory { dir, store ->
        val entries = modelFiles.map { (name, data) -> "vosk-test/$name" to data }
        val oversized = entries.map { (name, data) -> name to if (name.endsWith("final.mdl")) ByteArray(100_000) else data }
        val wrongHash = entries.map { (name, data) -> name to if (name.endsWith("final.mdl")) "wrong".toByteArray() else data }
        for (bad in listOf(zip(entries.drop(1)), zip(oversized), zip(wrongHash))) {
            assertThrows(IllegalStateException::class.java) { store.install(profile(bad), bad.inputStream(), { false }) }
            assertTrue(dir.list()!!.isEmpty())
        }
    }

    @Test fun zipWithoutPinnedExtractedManifestCannotBecomeReady() = withDirectory { dir, store ->
        val bytes = validZip()
        val unpinned = LocalModelProfile("vosk-test.zip", bytes.size.toLong(), sha(bytes))
        assertThrows(IllegalStateException::class.java) { store.install(unpinned, bytes.inputStream(), { false }) }
        assertNotEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to unpinned)).states()["vosk-test"])
    }

    @Test fun interruptedSourcePreservesPreviousModel() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val broken = object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("transfer interrupted")
        }
        assertThrows(java.io.IOException::class.java) { store.install(profile, broken, { false }) }
        assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun cancellationDuringExtractionPreservesThePreviousInstallation() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        var sourceEnded = false
        var checksAfterTransfer = 0
        val source = object : java.io.FilterInputStream(bytes.inputStream()) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(buffer, offset, length)
                if (count < 0) sourceEnded = true
                return count
            }
        }
        assertThrows(CancellationException::class.java) {
            store.install(profile, source, { sourceEnded && ++checksAfterTransfer >= 3 })
        }
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun activeModelReadLeaseProtectsItsDirectoryUntilClosed() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val readLease = LocalModelDirectoryLocks.forDirectory(dir).readLock()
        val prepared = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        readLease.lock()
        val replacement = Thread {
            try { store.install(profile, bytes.inputStream(), { prepared.countDown(); false }) }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            replacement.start()
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            assertFalse("Publication must wait for the runtime lease", finished.await(200, TimeUnit.MILLISECONDS))
            assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
        } finally { readLease.unlock() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertNull(failure.get())
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun cancellationWhileWaitingForARuntimeLeaseDoesNotReplaceTheModel() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val readLease = LocalModelDirectoryLocks.forDirectory(dir).readLock()
        val prepared = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        readLease.lock()
        val replacement = Thread {
            try { store.install(profile, bytes.inputStream(), { prepared.countDown(); cancelled.get() }) }
            catch (error: Throwable) { failure.set(error) }
            finally { finished.countDown() }
        }
        try {
            replacement.start()
            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            assertFalse(finished.await(200, TimeUnit.MILLISECONDS))
            cancelled.set(true)
            assertTrue("Cancellation must work before the runtime lease ends", finished.await(5, TimeUnit.SECONDS))
            assertTrue(failure.get() is CancellationException)
            assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
        } finally { readLease.unlock() }
        replacement.join(5000)
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun archiveTransferFilesLeftByATerminatedProcessAreRemoved() = withDirectory { dir, store ->
        val bytes = validZip()
        File(dir, "model-123.part").writeBytes(bytes.copyOf(bytes.size / 2))
        File(dir, "model-stage-456/vosk-test/am").mkdirs()
        File(dir, "model-stage-456/vosk-test/am/final.mdl").writeText("partial extraction")
        store.install(profile(bytes), bytes.inputStream(), { false })
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun aSweepNeverRemovesTheStagedDirectoryOfALiveTransfer() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        val lock = LocalModelDirectoryLocks.forDirectory(dir)
        val failure = AtomicReference<Throwable?>()
        val readLease = lock.readLock()
        readLease.lock()
        val transfer = Thread {
            try { VerifiedModelStore(dir).install(profile, bytes.inputStream(), { false }) }
            catch (error: Throwable) { failure.set(error) }
        }.also { it.isDaemon = true }
        try {
            transfer.start()
            // The transfer has extracted into its staging directory and waits to publish it.
            awaitQueued(lock, transfer)
            assertThrows(IllegalStateException::class.java) {
                store.install(profile, ByteArray(bytes.size).inputStream(), { false })
            }
        } finally { readLease.unlock() }
        transfer.join(5000)
        assertNull(failure.get())
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertEquals(listOf("vosk-test"), dir.list()!!.toList())
    }

    @Test fun inventoryChecksWaitForThePublicationTransaction() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        val writeLease = LocalModelDirectoryLocks.forDirectory(dir).writeLock()
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val state = AtomicReference<String?>()
        val root = File(dir, "vosk-test")
        val saved = File(dir, "saved-installation")
        writeLease.lock()
        val scanning = Thread {
            entered.countDown()
            state.set(inventory.states()["vosk-test"])
            finished.countDown()
        }
        try {
            Files.move(root.toPath(), saved.toPath())
            scanning.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse("Inventory must not observe the rename gap", finished.await(200, TimeUnit.MILLISECONDS))
            Files.move(saved.toPath(), root.toPath())
        } finally { writeLease.unlock() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals("ready", state.get())
    }

    @Test fun anUnexpectedRuntimeGraphCannotBypassThePinnedManifest() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        assertEquals("ready", inventory.states()["vosk-test"])
        File(dir, "vosk-test/graph/HCLG.fst").also { it.parentFile!!.mkdirs(); it.writeText("unverified graph") }
        assertNotEquals("ready", inventory.states()["vosk-test"])
    }

    @Test fun aRuntimeLeaseHolderCanCheckInventoryWhenAWriterAndScannerAreQueued() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        val lock = LocalModelDirectoryLocks.forDirectory(dir)
        val leased = CountDownLatch(1)
        val checkNow = CountDownLatch(1)
        val runtimeFinished = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val scannerFinished = CountDownLatch(1)
        val state = AtomicReference<String?>()
        val runtime = Thread {
            val read = lock.readLock()
            read.lock()
            try {
                leased.countDown()
                checkNow.await(5, TimeUnit.SECONDS)
                state.set(inventory.states()["vosk-test"])
            } finally { read.unlock(); runtimeFinished.countDown() }
        }.also { it.isDaemon = true }
        val writer = Thread {
            val write = lock.writeLock()
            write.lock()
            try { writerFinished.countDown() } finally { write.unlock() }
        }.also { it.isDaemon = true }
        val scanner = Thread {
            inventory.states()
            scannerFinished.countDown()
        }.also { it.isDaemon = true }
        runtime.start()
        assertTrue(leased.await(5, TimeUnit.SECONDS))
        writer.start()
        awaitQueued(lock, writer)
        scanner.start()
        awaitQueued(lock, scanner)
        checkNow.countDown()
        assertTrue("Inventory monitor must not deadlock the runtime read lease", runtimeFinished.await(2, TimeUnit.SECONDS))
        assertEquals("ready", state.get())
        assertTrue(writerFinished.await(5, TimeUnit.SECONDS))
        assertTrue(scannerFinished.await(5, TimeUnit.SECONDS))
    }

    @Test fun freshInventoryRecoversACompleteBackupAfterTerminationBetweenRenames() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val target = File(dir, "vosk-test")
        val backup = File(dir, ".model-backup-vosk-test")
        Files.move(target.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        assertFalse(target.exists())
        val restartedStore = VerifiedModelStore(dir)
        val restartedInventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        assertEquals("ready", restartedInventory.states()["vosk-test"])
        assertEquals("model", File(target, "am/final.mdl").readText())
        assertFalse(backup.exists())
        // Recovery precedes a later failed import, so persistence does not depend on downloading again.
        assertThrows(IllegalStateException::class.java) {
            restartedStore.install(profile, byteArrayOf().inputStream(), { false })
        }
        assertEquals("ready", restartedInventory.states()["vosk-test"])
    }

    @Test fun recoveryRetainsAValidPublishedModelAndCleansItsInterruptedBackup() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val backup = File(dir, ".model-backup-vosk-test")
        File(dir, "vosk-test").copyRecursively(backup)
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertFalse("A completed transaction must release its old copy", backup.exists())
    }

    @Test fun recoveryPrefersAValidBackupToAnInvalidPublishedDirectory() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val backup = File(dir, ".model-backup-vosk-test")
        File(dir, "vosk-test").copyRecursively(backup)
        File(dir, "vosk-test/am/final.mdl").writeText("wrong")
        assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertEquals("model", File(dir, "vosk-test/am/final.mdl").readText())
        assertFalse(backup.exists())
    }

    @Test fun recoveryDoesNotPublishAnIncompleteBackup() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val backup = File(dir, ".model-backup-vosk-test")
        Files.move(File(dir, "vosk-test").toPath(), backup.toPath())
        File(backup, "am/final.mdl").delete()
        assertNotEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        assertTrue("Preserve an invalid interrupted copy for repair instead of trusting it", backup.exists())
        assertFalse(File(dir, "vosk-test").exists())
    }

    @Test fun pendingRecoveryDoesNotUpgradeAnActiveRuntimeReadLease() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val backup = File(dir, ".model-backup-vosk-test")
        Files.move(File(dir, "vosk-test").toPath(), backup.toPath())
        val inventory = LocalModelInventory(dir, mapOf("vosk-test" to profile))
        val finished = CountDownLatch(1)
        val state = AtomicReference<String?>()
        val runtime = Thread {
            val lease = LocalModelDirectoryLocks.forDirectory(dir).readLock()
            lease.lock()
            try { state.set(inventory.states()["vosk-test"]) }
            finally { lease.unlock(); finished.countDown() }
        }.also { it.isDaemon = true }
        runtime.start()
        assertTrue("Recovery must never upgrade a held read lease", finished.await(2, TimeUnit.SECONDS))
        assertNotEquals("ready", state.get())
        assertTrue(backup.exists())
        assertEquals("ready", inventory.states()["vosk-test"])
    }

    @Test fun repairingANestedDirectoryLinkNeverDeletesOutsideFiles() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        store.install(profile, bytes.inputStream(), { false })
        val outside = Files.createTempDirectory("aeronex-outside-model-").toFile()
        val sentinel = File(outside, "keep.txt").also { it.writeText("unrelated content") }
        val link = File(dir, "vosk-test/unverified-link")
        try {
            createDirectoryLinkOrSkip(link, outside)
            assertNotEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
            store.install(profile, bytes.inputStream(), { false })
            assertEquals("unrelated content", sentinel.readText())
            assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
            assertEquals(listOf("vosk-test"), dir.list()!!.toList())
        } finally {
            Files.deleteIfExists(link.toPath())
            outside.deleteRecursively()
        }
    }

    @Test fun repairingALinkAtTheModelRootNeverDeletesOutsideFiles() = withDirectory { dir, store ->
        val bytes = validZip()
        val profile = profile(bytes)
        val outside = Files.createTempDirectory("aeronex-outside-model-").toFile()
        val sentinel = File(outside, "keep.txt").also { it.writeText("unrelated content") }
        val link = File(dir, "vosk-test")
        try {
            createDirectoryLinkOrSkip(link, outside)
            assertNotEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
            store.install(profile, bytes.inputStream(), { false })
            assertEquals("unrelated content", sentinel.readText())
            assertEquals("ready", LocalModelInventory(dir, mapOf("vosk-test" to profile)).states()["vosk-test"])
        } finally {
            if (Files.isSymbolicLink(link.toPath())) Files.deleteIfExists(link.toPath())
            outside.deleteRecursively()
        }
    }

    private fun createDirectoryLinkOrSkip(link: File, target: File) {
        try { Files.createSymbolicLink(link.toPath(), target.toPath()) }
        catch (unsupported: java.nio.file.FileSystemException) { org.junit.Assume.assumeNoException(unsupported) }
        catch (unsupported: UnsupportedOperationException) { org.junit.Assume.assumeNoException(unsupported) }
    }

    private fun awaitQueued(lock: java.util.concurrent.locks.ReentrantReadWriteLock, thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!lock.hasQueuedThread(thread) && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Thread must reach the directory lock queue", lock.hasQueuedThread(thread))
    }

    private fun replaceAscii(bytes: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val needle = from.toByteArray()
        val replaced = bytes.copyOf()
        for (index in 0..bytes.size - needle.size) {
            if (needle.indices.all { bytes[index + it] == needle[it] })
                to.toByteArray().copyInto(replaced, index)
        }
        return replaced
    }
}
