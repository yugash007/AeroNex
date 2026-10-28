package com.aeronex.app.session

import com.aeronex.app.ai.AiModelStore
import com.aeronex.app.ai.AiModels
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Models screen's AI rows over the session channel (0.4.0): the LFM planner and Needle tool-call models
 * ([AiModels]), each verified by size and SHA-256 before it is visible ([AiModelStore]). One download or import runs at
 * a time on its own thread ("aeronex-ai-models"); the channel call only starts it, and [status] shows its progress and
 * the last failure. An import reads `<importRoot>/<id>/<file>` (the adb push folder, as for the voice packs). [remove]
 * first lets [beforeRemove] stop the AI and free the planner. [changed] runs after every change (AI availability is
 * checked again).
 */
internal class AiModelSetup(
    private val store: AiModelStore,
    private val importRoot: File?,
    private val beforeRemove: () -> Unit,
    private val changed: () -> Unit,
) : AutoCloseable {
    private class Transfer(val id: String, val source: String, val total: Long) {
        @Volatile var received = 0L
        val cancelled = AtomicBoolean(false)
    }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aeronex-ai-models").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var transfer: Transfer? = null
    /** The last failed download or import: (model id, operator message). */
    @Volatile private var failure: Pair<String, String>? = null

    /**
     * `{available, reason, models: [{id, file, bytes, state, importPath}], transfer: {id, source, receivedBytes,
     * totalBytes}?, error: {id, message}?}`. Blocking: the first call hashes the model files (once per process).
     */
    fun status(ai: AiStatus): Map<String, Any?> {
        val states = store.states()
        return linkedMapOf(
            "available" to ai.available,
            "reason" to ai.reason,
            "models" to AiModels.profiles.map { (id, profile) ->
                linkedMapOf(
                    "id" to id,
                    "file" to profile.file,
                    "bytes" to profile.size,
                    "state" to (states[id] ?: "missing"),
                    "importPath" to importFile(id)?.path,
                )
            },
            "transfer" to transfer?.let {
                linkedMapOf("id" to it.id, "source" to it.source, "receivedBytes" to it.received, "totalBytes" to it.total)
            },
            "error" to failure?.let { (id, message) -> linkedMapOf("id" to id, "message" to message) },
        )
    }

    /** Starts the HTTPS download of [id]; [SessionException] when another transfer runs or [id] is unknown. */
    fun download(id: String) = start(id, DOWNLOAD) { transfer -> store.download(id, transfer.cancelled::get) { transfer.received = it } }

    /** Starts installing [id] from its import folder (verified like a download). */
    fun import(id: String) {
        val file = importFile(id) ?: throw SessionException(SessionException.BAD_ARGS, NO_IMPORT_FOLDER)
        if (!file.isFile) throw SessionException(SessionException.BAD_ARGS, "Copy ${file.name} to ${file.parent} first.")
        start(id, IMPORT) { transfer ->
            file.inputStream().use { store.install(id, it, transfer.cancelled::get) { count -> transfer.received = count } }
        }
    }

    /** Stops the running download or import (if any). */
    fun cancel() {
        transfer?.cancelled?.set(true)
    }

    /** Deletes [id]'s file after the AI stopped; whether it existed. */
    fun remove(id: String): Boolean {
        known(id)
        if (transfer?.id == id) throw SessionException(BUSY, "Wait for the ${name(id)} transfer to end, or cancel it.")
        beforeRemove()
        return try {
            store.remove(id)
        } finally {
            changed()
        }
    }

    override fun close() {
        cancel()
        worker.shutdownNow()
    }

    private fun start(id: String, source: String, work: (Transfer) -> Unit) {
        known(id)
        val started = synchronized(lock) {
            if (transfer != null) throw SessionException(BUSY, "Another AI model download or import is running.")
            Transfer(id, source, AiModels.profiles.getValue(id).size).also {
                transfer = it
                failure = null
            }
        }
        try {
            worker.execute {
                try {
                    work(started)
                } catch (error: Exception) {
                    failure = id to when {
                        started.cancelled.get() || error is CancellationException -> CANCELLED
                        source == IMPORT -> error.message ?: "The ${name(id)} file could not be imported."
                        else -> error.message ?: "The ${name(id)} download failed. Check the internet connection."
                    }
                } finally {
                    synchronized(lock) { transfer = null }
                    changed()
                }
            }
        } catch (error: RejectedExecutionException) {
            synchronized(lock) { transfer = null }
            throw SessionException(BUSY, "The AI model setup is closed.")
        }
        changed()
    }

    private fun known(id: String) {
        if (id !in AiModels.profiles) throw SessionException(SessionException.BAD_ARGS, "Unknown AI model: $id")
    }

    private fun importFile(id: String): File? =
        importRoot?.let { File(File(it, id), AiModels.profiles.getValue(id).file) }

    private fun name(id: String) = if (id == AiModels.LFM) "AI planner" else "AI tool-call"

    companion object {
        const val BUSY = "AI_MODELS"
        const val DOWNLOAD = "download"
        const val IMPORT = "import"
        const val CANCELLED = "Cancelled."
        const val NO_IMPORT_FOLDER = "This phone has no import folder. Download the model instead."
    }
}
