package com.aeronex.app.session

import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotPumpTest {
    private val built = AtomicInteger()
    private val delivered = CopyOnWriteArrayList<Map<String, Any?>>()
    private var pump: SnapshotPump? = null

    @After fun tearDown() {
        pump?.close()
    }

    private fun start(periodMs: Long, deliver: (Map<String, Any?>) -> Unit = { delivered += it }) =
        SnapshotPump(periodMs, { mapOf("seq" to built.incrementAndGet()) }, deliver).also {
            pump = it
            it.start()
        }

    /**
     * Count-based, so a loaded machine cannot flake it: the n-th periodic snapshot is never due before (n - 1) periods
     * (fixed rate), and a slow machine only makes it later.
     */
    @Test fun deliversAtTheRateAndStopsWhenClosed() {
        val started = System.nanoTime()
        val p = start(50)
        waitFor("10 snapshots", 10_000) { delivered.size >= 10 }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("10 snapshots at 20 Hz take at least 9 periods: $tookMs ms", tookMs >= 9 * 50 - 5)
        p.close()
        Thread.sleep(100) // one already being delivered may still land
        val n = delivered.size
        Thread.sleep(300)
        assertEquals("nothing after close", n, delivered.size)
    }

    @Test fun aPokeDeliversAtOnce() {
        val p = start(10_000)
        waitFor("the first snapshot") { delivered.size == 1 }
        p.poke()
        waitFor("pushed without waiting for the period", 1_000) { delivered.size == 2 }
    }

    @Test fun pokesCoalesceWhileOneIsQueued() {
        val gate = CountDownLatch(1)
        val p = start(10_000) { gate.await(5, TimeUnit.SECONDS); delivered += it }
        repeat(100) { p.poke() } // the pump thread is blocked delivering the first snapshot
        gate.countDown()
        waitFor("drained") { delivered.size >= 2 }
        Thread.sleep(200)
        assertTrue("one queued poke, not 100: ${delivered.size}", delivered.size <= 3)
    }

    @Test fun aFailingDeliveryDoesNotStopThePump() {
        val calls = AtomicInteger()
        start(30) { if (calls.incrementAndGet() == 1) throw IllegalStateException("sink gone") else delivered += it }
        waitFor("still delivering") { delivered.size >= 3 }
    }

    @Test fun latestOnlyKeepsAtMostOnePostQueuedAndDeliversTheNewestValue() {
        val posted = CopyOnWriteArrayList<Runnable>()
        val seen = CopyOnWriteArrayList<Int>()
        val latest = LatestOnly<Int>(post = { posted += it }, sink = { seen += it })
        for (i in 1..50) latest.offer(i) // the main thread is busy: nothing runs yet
        assertEquals(1, posted.size)
        posted.removeAt(0).run()
        assertEquals(listOf(50), seen)
        latest.offer(51)
        assertEquals(1, posted.size)
        posted.removeAt(0).run()
        assertEquals(listOf(50, 51), seen)
    }

    @Test fun latestOnlyLosesNothingWhenOffersRaceTheDelivery() {
        val main = Executors.newSingleThreadExecutor()
        val seen = CopyOnWriteArrayList<Int>()
        val latest = LatestOnly<Int>(post = { main.execute(it) }, sink = { seen += it })
        for (i in 1..2_000) latest.offer(i)
        waitFor("the last value arrives") { seen.lastOrNull() == 2_000 }
        assertEquals(seen.sorted(), seen)
        main.shutdown()
    }
}
