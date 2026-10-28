package com.aeronex.app.memory

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Where the flight memory lives between runs. [save] must not block the caller for I/O. */
interface JournalStore {
    /** The saved memory, or null when there is none or it cannot be read (a damaged file starts empty). */
    fun load(): MemoryState?

    fun save(state: MemoryState)

    companion object {
        /** No persistence (host tests, and sessions without a files directory). */
        val NONE: JournalStore = object : JournalStore {
            override fun load(): MemoryState? = null
            override fun save(state: MemoryState) {}
        }
    }
}

/**
 * The memory as one JSON file ([MemoryCodec]), written on [io]: a burst of changes writes only the newest state.
 * Each write goes to a temporary file that then replaces [file], so a crash mid-write leaves the last good copy.
 * Write errors are counted, never thrown: losing the memory must never stop a flight.
 */
class FileJournalStore(
    private val file: File,
    private val io: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aeronex-memory").apply { isDaemon = true }
    },
) : JournalStore {
    private val latest = AtomicReference<MemoryState?>(null)

    @Volatile var writeErrors = 0L
        private set

    override fun load(): MemoryState? = try {
        if (file.isFile) MemoryCodec.decode(file.readText(Charsets.UTF_8)) else null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun save(state: MemoryState) {
        if (latest.getAndSet(state) != null) return // a write is already queued; it takes the newest state
        io.execute { latest.getAndSet(null)?.let(::write) }
    }

    private fun write(state: MemoryState) {
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(MemoryCodec.encode(state), Charsets.UTF_8)
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (_: IOException) {
            writeErrors++
        } catch (_: SecurityException) {
            writeErrors++
        }
    }
}
