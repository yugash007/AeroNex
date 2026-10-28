package com.aeronex.app.speech

import android.content.Context
import com.aeronex.app.LocalModelInventory
import com.aeronex.app.LocalModelProfile
import com.aeronex.app.VerifiedModelStore
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/**
 * Installs and checks the sherpa-onnx speech packs (spec 4.19). Packs are not bundled in the APK. A pack is installed
 * file by file into `<root>/<pack-id>/` through [VerifiedModelStore], so a file becomes visible only after its size
 * and SHA-256 match the manifest. Two sources:
 * - [download]: the manifest's pinned HTTPS URLs (only files not already verified), started by the operator on the
 *   Models screen;
 * - [import]: `<importRoot>/<pack-id>/<file>` (`getExternalFilesDir(null)/import/...`: the adb push target for
 *   development and the SD/USB field kit). Files are verified while they are copied in; what runs is the verified copy,
 *   never the import folder. The import copy is left in place.
 *
 * [start] runs either on the [installer] thread and is single-flight (0.4.0, T15 follow-up): one install per pack per
 * process, whatever its source and whichever store instance asked; a second request joins the running [Install], whose
 * progress, cancel and outcome the Models screen and the voice session both see. The blocking [import] and [download]
 * hold the same per-pack lock, so no two copies of a pack ever run at once. An install hashes the published files
 * once more before it is reported done, and every store of the process shares those checks: the voice session never
 * hashes a 200 MB pack again on the speech thread.
 *
 * Vosk models keep their own store. Blocking calls: run them on a worker thread, never the main thread.
 */
class ModelPackStore(
    val root: File,
    val importRoot: File?,
    private val installer: Executor = installerThread(),
    private val downloader: Downloader = Downloader { store, profile, cancelled, progress ->
        store.download(profile, cancelled, progress)
    },
) {
    /** Fetches one file over HTTPS into the verified store (the transport seam; tests serve the bytes themselves). */
    fun interface Downloader {
        fun fetch(store: VerifiedModelStore, profile: LocalModelProfile, cancelled: () -> Boolean, progress: (Long) -> Unit)
    }

    enum class State { READY, MISSING, PARTIAL, INVALID, CHECKING, UNREADABLE }

    /** One running or finished install of a pack: progress ([bytes] of [total]), [cancel] and the outcome. */
    class Install internal constructor(val pack: SpeechModelPack, val source: Source) {
        enum class Source { DOWNLOAD, IMPORT }

        private val cancelRequested = AtomicBoolean()
        private val done = CountDownLatch(1)
        private val listeners = mutableListOf<(Install) -> Unit>()

        /** Bytes verified so far, files already installed included. */
        @Volatile var bytes: Long = 0
            internal set
        val total: Long get() = pack.bytes

        /** Why it did not install (a [CancellationException] after [cancel]); null while running or when installed. */
        @Volatile var error: Exception? = null
            private set
        val finished: Boolean get() = done.count == 0L
        val cancelled: Boolean get() = cancelRequested.get()
        val installed: Boolean get() = finished && error == null

        /** Stops the install at its next 64 KiB step; nothing unverified is ever published. */
        fun cancel() = cancelRequested.set(true)

        fun await(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

        fun await() = done.await()

        /** Calls [listener] once when the install ends (on the installer thread), or now when it already has. */
        fun whenFinished(listener: (Install) -> Unit) {
            synchronized(listeners) {
                if (!finished) {
                    listeners += listener
                    return
                }
            }
            listener(this)
        }

        internal fun finish(failure: Exception?) {
            val waiting = synchronized(listeners) {
                error = failure
                done.countDown()
                listeners.toList().also { listeners.clear() }
            }
            waiting.forEach { it(this) }
        }
    }

    /** Pack id to its registry key; the voice session polls [installing] and [completions] every frame. */
    private val keys = ConcurrentHashMap<String, String>()

    fun directory(pack: SpeechModelPack) = File(root, pack.id)

    fun importDirectory(pack: SpeechModelPack): File? = importRoot?.let { File(it, pack.id) }

    /** Hashes each file once per launch (and again only when it changes). */
    fun state(pack: SpeechModelPack): State {
        val states = fileStates(pack).values
        return when {
            states.all { it == "ready" } -> State.READY
            states.all { it == "missing" } -> State.MISSING
            "invalid" in states -> State.INVALID
            "unreadable" in states -> State.UNREADABLE
            "checking" in states -> State.CHECKING
            else -> State.PARTIAL
        }
    }

    /** The verified files by role, or null unless the whole pack is ready. */
    fun readyFiles(pack: SpeechModelPack): Map<String, File>? =
        if (state(pack) == State.READY) pack.files.associate { it.role to File(directory(pack), it.name) } else null

    fun readyPacks(packs: List<SpeechModelPack>): List<SpeechModelPack> = packs.filter { state(it) == State.READY }

    /** Whether every file of [pack] is present in its import folder (not yet verified). */
    fun importAvailable(pack: SpeechModelPack): Boolean {
        val folder = importDirectory(pack) ?: return false
        return pack.files.all { File(folder, it.name).isFile }
    }

    /**
     * Starts installing [pack] from [source] on the installer thread, or returns the install of [pack] that is already
     * running (single-flight).
     */
    fun start(pack: SpeechModelPack, source: Install.Source): Install {
        val key = key(pack)
        val install = synchronized(RUNNING) {
            RUNNING[key]?.takeIf { !it.finished }?.let { return it }
            Install(pack, source).also { RUNNING[key] = it }
        }
        try {
            installer.execute { run(install) }
        } catch (error: Exception) {
            install.finish(error)
        }
        return install
    }

    /** The install of [pack] running now, if any. */
    fun installing(pack: SpeechModelPack): Install? = RUNNING[key(pack)]?.takeIf { !it.finished }

    /** The latest install of [pack] in this process, running or finished (a failure keeps its [Install.error]). */
    fun lastInstall(pack: SpeechModelPack): Install? = RUNNING[key(pack)]

    /** How many installs of [pack] succeeded in this process: a change means its files changed. */
    fun completions(pack: SpeechModelPack): Long = COMPLETED[key(pack)] ?: 0L

    /**
     * The release app's install route for speech packs (adb push or field-kit copy, then the next voice start): starts
     * importing each of [packs] that is not ready but has a complete import folder, and joins any install of them that
     * is already running. Never copies on the caller's thread.
     */
    fun startWaitingImports(packs: List<SpeechModelPack>): List<Install> = packs.mapNotNull { pack ->
        installing(pack) ?: if (state(pack) != State.READY && importAvailable(pack)) start(pack, Install.Source.IMPORT) else null
    }

    /**
     * [startWaitingImports], then waits for them. A pack that fails is reported to [failed]; the others still import.
     * Returns the ids installed.
     */
    fun importWaiting(packs: List<SpeechModelPack>, failed: (SpeechModelPack, Exception) -> Unit): List<String> =
        startWaitingImports(packs).mapNotNull { install ->
            install.await()
            install.error?.let { failed(install.pack, it) }
            install.pack.id.takeIf { install.installed }
        }

    /** Copies [pack] in from its import folder, verifying each file; refuses before copying if any file is absent. */
    fun import(pack: SpeechModelPack, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val folder = checkNotNull(importDirectory(pack)) { "No import folder on this phone." }
        val absent = pack.files.filterNot { File(folder, it.name).isFile }.map { it.name }
        check(absent.isEmpty()) { "Copy ${absent.joinToString()} into ${folder.path} first." }
        install(pack, cancelled, progress) { store, file, fileProgress ->
            File(folder, file.name).inputStream().use { store.install(file.profile, it, cancelled, fileProgress) }
        }
    }

    /** Downloads the files of [pack] that are not verified yet from the manifest's HTTPS URLs. */
    fun download(pack: SpeechModelPack, cancelled: () -> Boolean, progress: (Long) -> Unit) {
        val noUrl = pack.files.filter { it.url == null }.map { it.name }
        check(noUrl.isEmpty()) {
            "${pack.id} has no download address for ${noUrl.joinToString()}: import it into ${importDirectory(pack)?.path}."
        }
        install(pack, cancelled, progress) { store, file, fileProgress ->
            downloader.fetch(store, file.profile, cancelled, fileProgress)
        }
    }

    private fun run(install: Install) {
        val cancelled = { install.cancelled }
        val progress: (Long) -> Unit = { install.bytes = it }
        val failure = try {
            when (install.source) {
                Install.Source.DOWNLOAD -> download(install.pack, cancelled, progress)
                Install.Source.IMPORT -> import(install.pack, cancelled, progress)
            }
            COMPLETED.merge(key(install.pack), 1L, Long::plus)
            null
        } catch (error: Exception) {
            error
        } catch (error: Throwable) {
            // Out of memory or the like: report it rather than leave the install running forever.
            IllegalStateException("The voice pack could not be installed: ${error.javaClass.simpleName}", error)
        }
        install.finish(failure)
    }

    private fun install(
        pack: SpeechModelPack,
        cancelled: () -> Boolean,
        progress: (Long) -> Unit,
        copy: (VerifiedModelStore, PackFile, (Long) -> Unit) -> Unit,
    ) {
        val lock = LOCKS.computeIfAbsent(key(pack)) { ReentrantLock() }
        while (!lock.tryLock(100, TimeUnit.MILLISECONDS)) {
            if (cancelled()) throw CancellationException("Model setup cancelled.")
        }
        try {
            val store = VerifiedModelStore(directory(pack))
            val states = fileStates(pack)
            var done = 0L
            for (file in pack.files) {
                if (states[file.name] != "ready") {
                    val base = done
                    copy(store, file) { progress(base + it) }
                }
                done += file.bytes
                progress(done)
            }
            inventory(pack).let { inventory -> pack.files.forEach { inventory.invalidate(it.name) } }
            // Hash the published files here, on the installer: the stores that poll the pack then find them checked.
            fileStates(pack)
        } finally {
            lock.unlock()
        }
    }

    private fun fileStates(pack: SpeechModelPack): Map<String, String> = inventory(pack).states()

    private fun inventory(pack: SpeechModelPack): LocalModelInventory =
        INVENTORIES.compute(key(pack)) { _, known ->
            known?.takeIf { it.first == pack.files }
                ?: (pack.files to LocalModelInventory(directory(pack), pack.files.associate { it.name to it.profile }))
        }!!.second

    private fun key(pack: SpeechModelPack): String =
        keys.computeIfAbsent(pack.id) { directory(pack).absoluteFile.normalize().path }

    companion object {
        /** Installs by pack directory, process-wide: the Models screen and the voice session share them. */
        private val RUNNING = ConcurrentHashMap<String, Install>()
        private val COMPLETED = ConcurrentHashMap<String, Long>()
        private val LOCKS = ConcurrentHashMap<String, ReentrantLock>()
        /** Each pack directory's checked file states (with the files they were checked against), process-wide. */
        private val INVENTORIES = ConcurrentHashMap<String, Pair<List<PackFile>, LocalModelInventory>>()

        /**
         * One daemon thread that runs installs in order and goes away when idle. [setup] runs first on it (a phone
         * lowers its priority: a 200 MB copy and hash must never compete with the link or speech threads).
         */
        fun installerThread(setup: () -> Unit = {}): Executor =
            ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()) { task ->
                Thread({
                    try {
                        setup()
                    } catch (_: Exception) {
                        // Default priority rather than no install.
                    }
                    task.run()
                }, "aeronex-pack-install").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }

        /** `filesDir/speech-packs`, importing from `getExternalFilesDir(null)/import`. */
        fun forContext(context: Context) = ModelPackStore(
            File(context.filesDir, "speech-packs"),
            context.getExternalFilesDir(null)?.let { File(it, "import") },
            installerThread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) },
        )
    }
}
