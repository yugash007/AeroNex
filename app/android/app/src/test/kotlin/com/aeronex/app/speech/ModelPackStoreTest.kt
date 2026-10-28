package com.aeronex.app.speech

import com.aeronex.app.LocalModelProfile
import com.aeronex.app.VerifiedModelStore
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackStoreTest {
    private val temp = Files.createTempDirectory("aeronex-packs").toFile()
    private val root = File(temp, "speech-packs")
    private val importRoot = File(temp, "external/import")
    private val model = "model weights ".repeat(1000).toByteArray()
    private val tokens = "<blk> 0\n▁a 1\n".toByteArray()
    private val pack = SpeechModelPack("indicconformer-xx-ctc-int8", PackEngine.NEMO_CTC, "xx", false, DeviceTier.M,
        null, "MIT", true, 1, true, listOf(
            PackFile("model.int8.onnx", "model", model.size.toLong(), sha(model), "https://example.test/xx/model.int8.onnx"),
            PackFile("tokens.txt", "tokens", tokens.size.toLong(), sha(tokens), "https://example.test/tokens.txt")))
    private val fetched = mutableListOf<String>()
    private val store = ModelPackStore(root, importRoot) { target, profile, cancelled, progress ->
        fetched += profile.url
        target.install(profile, (if (profile.file == "tokens.txt") tokens else model).inputStream(), cancelled, progress)
    }

    @After fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun stage(name: String, bytes: ByteArray) {
        File(importRoot, pack.id).mkdirs()
        File(importRoot, "${pack.id}/$name").writeBytes(bytes)
    }

    @Test fun nothingInstalledIsMissingAndHasNoFiles() {
        assertEquals(ModelPackStore.State.MISSING, store.state(pack))
        assertNull(store.readyFiles(pack))
        assertFalse(store.importAvailable(pack))
        assertEquals(File(importRoot, pack.id), store.importDirectory(pack))
    }

    @Test fun anImportFolderIsVerifiedThenCopiedIn() {
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        assertTrue(store.importAvailable(pack))
        val progress = mutableListOf<Long>()
        store.import(pack, { false }, { progress += it })
        assertEquals(ModelPackStore.State.READY, store.state(pack))
        val files = store.readyFiles(pack)!!
        assertTrue(model.contentEquals(files.getValue("model").readBytes()))
        assertTrue(tokens.contentEquals(files.getValue("tokens").readBytes()))
        assertEquals(File(root, pack.id), files.getValue("model").parentFile)
        assertEquals(pack.bytes, progress.last())
        assertTrue("the operator's import copy is left alone", File(importRoot, "${pack.id}/model.int8.onnx").isFile)
    }

    @Test fun aCorruptImportIsRefusedAndNeverBecomesReady() {
        stage("model.int8.onnx", model.copyOf().also { it[10] = 'X'.code.toByte() })
        stage("tokens.txt", tokens)
        assertThrows(IllegalStateException::class.java) { store.import(pack, { false }, {}) }
        assertFalse(store.state(pack) == ModelPackStore.State.READY)
        assertNull(store.readyFiles(pack))
        assertFalse("nothing unverified is published", File(root, "${pack.id}/model.int8.onnx").exists())
    }

    /** The release app's only install route for speech packs (T15): an adb push into the import folder. */
    @Test fun waitingImportFoldersAreImportedOnceAndIncompleteOnesAreLeftAlone() {
        val other = pack.copy(id = "indicconformer-yy-ctc-int8", language = "yy")
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        File(importRoot, other.id).mkdirs()
        File(importRoot, "${other.id}/model.int8.onnx").writeBytes(model)
        val failed = mutableListOf<String>()
        assertEquals(listOf(pack.id), store.importWaiting(listOf(pack, other)) { waiting, _ -> failed += waiting.id })
        assertEquals(ModelPackStore.State.READY, store.state(pack))
        assertEquals(ModelPackStore.State.MISSING, store.state(other))
        assertEquals("a ready pack is not copied again", emptyList<String>(),
            store.importWaiting(listOf(pack, other)) { waiting, _ -> failed += waiting.id })
        assertEquals(emptyList<String>(), failed)
    }

    @Test fun aCorruptWaitingImportIsReportedAndTheOthersStillImport() {
        val corrupt = pack.copy(id = "indicconformer-aa-ctc-int8")
        File(importRoot, corrupt.id).mkdirs()
        File(importRoot, "${corrupt.id}/model.int8.onnx").writeBytes(model.copyOf().also { it[10] = 'X'.code.toByte() })
        File(importRoot, "${corrupt.id}/tokens.txt").writeBytes(tokens)
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        val failed = mutableListOf<String>()
        assertEquals(listOf(pack.id), store.importWaiting(listOf(corrupt, pack)) { waiting, _ -> failed += waiting.id })
        assertEquals(listOf(corrupt.id), failed)
        assertEquals(ModelPackStore.State.READY, store.state(pack))
        assertNull(store.readyFiles(corrupt))
    }

    @Test fun anIncompleteImportFolderIsRefusedBeforeCopying() {
        stage("model.int8.onnx", model)
        assertFalse(store.importAvailable(pack))
        val error = assertThrows(IllegalStateException::class.java) { store.import(pack, { false }, {}) }
        assertTrue(error.message!!, error.message!!.contains("tokens.txt"))
        assertEquals(ModelPackStore.State.MISSING, store.state(pack))
    }

    @Test fun aPartlyInstalledPackIsNotReady() {
        stage("tokens.txt", tokens)
        stage("model.int8.onnx", model)
        store.import(pack, { false }, {})
        File(root, "${pack.id}/model.int8.onnx").delete()
        assertEquals(ModelPackStore.State.PARTIAL, store.state(pack))
        File(root, "${pack.id}/model.int8.onnx").writeBytes(model.copyOf(100))
        assertEquals(ModelPackStore.State.INVALID, store.state(pack))
        assertNull(store.readyFiles(pack))
    }

    @Test fun aDownloadFetchesOnlyMissingFilesOverVerifiedHttps() {
        stage("tokens.txt", tokens)
        stage("model.int8.onnx", model)
        store.import(pack, { false }, {})
        File(root, "${pack.id}/tokens.txt").delete()
        store.download(pack, { false }, {})
        assertEquals(listOf("https://example.test/tokens.txt"), fetched)
        assertEquals(ModelPackStore.State.READY, store.state(pack))
    }

    @Test fun aPackWithoutUrlsCanOnlyBeImported() {
        val importOnly = pack.copy(files = pack.files.map { it.copy(url = null) })
        val error = assertThrows(IllegalStateException::class.java) { store.download(importOnly, { false }, {}) }
        assertTrue(error.message!!, error.message!!.contains("import"))
        assertTrue(fetched.isEmpty())
    }

    /**
     * A fake HTTPS server behind the [ModelPackStore.Downloader] seam: it streams [body] for a URL in 4 KiB chunks
     * (and waits on [gate] after the first chunk when set), exactly as the verified HTTPS download hands its stream to
     * the store.
     */
    private inner class FakeHttps(private val body: (String) -> ByteArray) : ModelPackStore.Downloader {
        val requested = CopyOnWriteArrayList<String>()
        @Volatile var gate: CountDownLatch? = null
        val firstChunk = CountDownLatch(1)

        override fun fetch(store: VerifiedModelStore, profile: LocalModelProfile, cancelled: () -> Boolean,
                           progress: (Long) -> Unit) {
            requested += profile.url
            val bytes = body(profile.url)
            val stream = object : InputStream() {
                private var position = 0
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= bytes.size) return -1
                    if (position > 0) {
                        firstChunk.countDown()
                        gate?.await(5, TimeUnit.SECONDS)
                    }
                    val n = minOf(length, 4096, bytes.size - position)
                    System.arraycopy(bytes, position, buffer, offset, n)
                    position += n
                    return n
                }
            }
            store.install(profile, stream, cancelled, progress)
        }
    }

    private fun served(url: String) = if (url.endsWith("tokens.txt")) tokens else model

    private fun asyncStore(https: FakeHttps) = ModelPackStore(root, importRoot, ModelPackStore.installerThread(), https)

    private fun leftovers() = File(root, pack.id).listFiles().orEmpty().filter { it.name.endsWith(".part") }

    @Test fun aDownloadRunsOffTheCallerWithProgressAndBecomesReady() {
        val https = FakeHttps(::served)
        val store = asyncStore(https)
        val install = store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        assertEquals(pack.bytes, install.total)
        assertTrue(install.await(5, TimeUnit.SECONDS))
        assertTrue(install.installed)
        assertNull(install.error)
        assertEquals(pack.bytes, install.bytes)
        assertEquals(listOf("https://example.test/xx/model.int8.onnx", "https://example.test/tokens.txt"), https.requested.toList())
        assertEquals(ModelPackStore.State.READY, store.state(pack))
        assertNull("nothing is running now", store.installing(pack))
        assertEquals(1L, store.completions(pack))
    }

    @Test fun aSecondRequestJoinsTheRunningInstallWhateverItsSource() {
        val https = FakeHttps(::served).apply { gate = CountDownLatch(1) }
        val store = asyncStore(https)
        val first = store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        assertTrue(https.firstChunk.await(5, TimeUnit.SECONDS))
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        assertSame("single-flight", first, store.start(pack, ModelPackStore.Install.Source.DOWNLOAD))
        assertSame("a voice-start import joins the download", first, store.startWaitingImports(listOf(pack)).single())
        assertSame(first, store.installing(pack))
        assertTrue("progress is visible while it runs", first.bytes in 1 until pack.bytes)
        https.gate!!.countDown()
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertEquals("each file fetched once", 2, https.requested.size)
        assertEquals(1L, store.completions(pack))
        assertEquals(ModelPackStore.Install.Source.DOWNLOAD, first.source)
    }

    @Test fun cancellingADownloadPublishesNothingAndLeavesNoPartialFile() {
        val https = FakeHttps(::served).apply { gate = CountDownLatch(1) }
        val store = asyncStore(https)
        val install = store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        assertTrue(https.firstChunk.await(5, TimeUnit.SECONDS))
        install.cancel()
        https.gate!!.countDown()
        assertTrue(install.await(5, TimeUnit.SECONDS))
        assertTrue(install.cancelled)
        assertFalse(install.installed)
        assertTrue(install.error is CancellationException)
        assertEquals(ModelPackStore.State.MISSING, store.state(pack))
        assertTrue(leftovers().isEmpty())
        assertEquals(0L, store.completions(pack))
        assertTrue("a new request starts afresh", store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
            .also { it.await(5, TimeUnit.SECONDS) }.installed)
    }

    @Test fun aDownloadWhoseHashDoesNotMatchIsDeletedAndReported() {
        val https = FakeHttps { url -> if (url.endsWith("tokens.txt")) tokens else model.copyOf().also { it[10] = 'X'.code.toByte() } }
        val store = asyncStore(https)
        val install = store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        assertTrue(install.await(5, TimeUnit.SECONDS))
        assertFalse(install.installed)
        assertFalse(install.cancelled)
        assertTrue(install.error!!.message!!, install.error!!.message!!.contains("does not match"))
        assertFalse("nothing unverified is published", File(root, "${pack.id}/model.int8.onnx").exists())
        assertTrue("the partial download is deleted", leftovers().isEmpty())
        assertNull(store.readyFiles(pack))
        assertSame("the last install keeps its error for the Models screen", install, store.lastInstall(pack))
        assertEquals(0L, store.completions(pack))
    }

    /** T15 follow-up: two voice starts during one import never copy the pack twice. */
    @Test fun waitingImportsAreSingleFlightAndAwaitable() {
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        var runs = 0
        val store = ModelPackStore(root, importRoot, { task ->
            Thread {
                runs++
                started.countDown()
                gate.await(5, TimeUnit.SECONDS)
                task.run()
            }.apply { isDaemon = true }.start()
        })
        val first = store.startWaitingImports(listOf(pack)).single()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(ModelPackStore.Install.Source.IMPORT, first.source)
        assertSame(first, store.startWaitingImports(listOf(pack)).single())
        gate.countDown()
        assertEquals(listOf(pack.id), store.importWaiting(listOf(pack)) { _, error -> throw error })
        assertEquals(1, runs)
        assertTrue(first.installed)
        assertTrue("ready: nothing is waiting any more", store.startWaitingImports(listOf(pack)).isEmpty())
    }

    @Test fun finishedListenersRunOnceEvenWhenAddedLate() {
        val store = asyncStore(FakeHttps(::served))
        val install = store.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        val early = CopyOnWriteArrayList<Boolean>()
        install.whenFinished { early += it.installed }
        assertTrue(install.await(5, TimeUnit.SECONDS))
        val late = mutableListOf<Boolean>()
        install.whenFinished { late += it.installed }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (early.isEmpty() && System.nanoTime() < deadline) Thread.sleep(2)
        assertEquals(listOf(true), early.toList())
        assertEquals(listOf(true), late)
    }

    /** The installer checks the published pack, so another store (the voice session's) never hashes it again on its thread. */
    @Test fun anInstallIsHashedOnTheInstallerAndEveryStoreOfTheProcessTrustsIt() {
        val queued = CopyOnWriteArrayList<Runnable>()
        val installer = ModelPackStore(root, importRoot, { queued += it }) { target, profile, cancelled, progress ->
            target.install(profile, (if (profile.file == "tokens.txt") tokens else model).inputStream(), cancelled, progress)
        }
        val voice = ModelPackStore(root, importRoot)
        assertEquals(ModelPackStore.State.MISSING, voice.state(pack))
        val install = installer.start(pack, ModelPackStore.Install.Source.DOWNLOAD)
        queued.removeAt(0).run()
        assertTrue(install.installed)
        // Rewritten in place with the same size and time: only a second hash could tell.
        val file = File(root, "${pack.id}/model.int8.onnx").toPath()
        val time = Files.getLastModifiedTime(file)
        Files.write(file, model.copyOf().also { it[0] = 'X'.code.toByte() })
        Files.setLastModifiedTime(file, time)
        assertEquals(ModelPackStore.State.READY, voice.state(pack))
    }

    @Test fun readyPacksAreListedFromTheCatalogue() {
        stage("model.int8.onnx", model)
        stage("tokens.txt", tokens)
        store.import(pack, { false }, {})
        val other = pack.copy(id = "indicconformer-yy-ctc-int8", language = "yy")
        assertEquals(listOf(pack), store.readyPacks(listOf(pack, other)))
    }
}
