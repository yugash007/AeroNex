package com.aeronex.app.session

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds a snapshot every [periodMs] on its own thread and hands it to [deliver]; [poke] adds one at once (a STOP or
 * plan change) without waiting for the period. Pokes coalesce: at most one is queued, so nothing grows without bound
 * however often the state changes. A failing [deliver] or [snapshot] never stops the schedule.
 */
class SnapshotPump(
    private val periodMs: Long,
    private val snapshot: () -> Map<String, Any?>,
    private val deliver: (Map<String, Any?>) -> Unit,
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aeronex-session-snapshots").apply { isDaemon = true }
    }
    private val pokeQueued = AtomicBoolean(false)

    init {
        require(periodMs > 0) { "Snapshot period must be positive" }
    }

    fun start() {
        executor.scheduleAtFixedRate(::emit, 0, periodMs, TimeUnit.MILLISECONDS)
    }

    fun poke() {
        if (!pokeQueued.compareAndSet(false, true)) return
        try {
            executor.execute {
                pokeQueued.set(false)
                emit()
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun emit() {
        try {
            deliver(snapshot())
        } catch (_: Exception) {
            // The next period tries again.
        }
    }
}

/**
 * Hands the newest value to [sink] through [post] (e.g. the main thread's Handler) with at most one post queued: a
 * stalled main thread then receives only the latest snapshot when it wakes, never a backlog.
 */
class LatestOnly<T : Any>(private val post: (Runnable) -> Unit, private val sink: (T) -> Unit) {
    private val latest = AtomicReference<T?>(null)
    private val posted = AtomicBoolean(false)

    fun offer(value: T) {
        latest.set(value)
        if (posted.compareAndSet(false, true)) post(Runnable {
            posted.set(false)
            latest.getAndSet(null)?.let(sink)
        })
    }
}
