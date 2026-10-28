package com.aeronex.app.speech

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IndicConformer model loads off the speech thread at the first PTT or a pre-warm, never at voice start (0.4.0). */
class LazyCtcEngineTest {
    private val log = CopyOnWriteArrayList<String>()
    private val residency = Semaphore(1)
    /** The loader runs a task when the test says so. */
    private val queued = CopyOnWriteArrayList<Runnable>()

    private inner class FakeDecoder(val spec: CtcModelSpec) : CtcDecoder {
        val decoded = CopyOnWriteArrayList<Int>()
        override fun decode(samples: FloatArray): String {
            decoded += samples.size
            return "మూడు మీటర్లు టేకాఫ్"
        }
        override fun close() {
            log += "close ${spec.language}"
        }
    }

    private val decoders = CopyOnWriteArrayList<FakeDecoder>()
    private var gate: CountDownLatch? = null
    /** Counted down when a load reaches the decoder (the native model load). */
    private val entered = CountDownLatch(1)
    private var failLoad = false

    private fun spec(language: String) =
        CtcModelSpec(language, File("/packs/$language/model.int8.onnx"), File("/packs/$language/tokens.txt"), 4)

    private fun engine(language: String = "te", loader: (Runnable) -> Unit = { queued += it }, waitMs: Long = 5_000) =
        LazyCtcEngine(spec(language), { spec ->
            entered.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (failLoad) throw OutOfMemoryError("300 MiB")
            log += "load ${spec.language}"
            FakeDecoder(spec).also { decoders += it }
        }, loader, residency, residencyWaitMs = waitMs)

    private fun runQueued() {
        while (queued.isNotEmpty()) queued.removeAt(0).run()
    }

    private fun thread(task: Runnable) = Thread(task, "aeronex-ctc-load").apply { isDaemon = true; start() }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $what" }
            Thread.sleep(2)
        }
    }

    @Test fun nothingLoadsUntilTheFirstPressAndTheAudioWaitsForTheModel() {
        val engine = engine()
        assertEquals(EngineKind.SHERPA_CTC, engine.kind)
        assertEquals("te", engine.language)
        assertFalse(engine.streaming)
        assertEquals("voice start loads nothing", ModelLoad.COLD, engine.modelLoad)
        assertTrue(queued.isEmpty())
        engine.begin()
        assertEquals("the first press starts the load", ModelLoad.LOADING, engine.modelLoad)
        val pcm = ShortArray(3200) { 1000 }
        assertFalse(engine.accept(pcm, 0, pcm.size))
        assertTrue("the speech thread never waits for the model", decoders.isEmpty())
        runQueued()
        assertEquals(ModelLoad.READY, engine.modelLoad)
        val result = engine.finish()
        assertEquals("the whole buffered utterance is decoded", listOf(3200), decoders.single().decoded.toList())
        assertEquals(listOf("మూడు మీటర్లు టేకాఫ్"), result.alternatives.map { it.text })
        assertEquals(3200L, result.audioSamples)
        engine.begin()
        assertTrue("loaded once per engine", queued.isEmpty())
        engine.close()
        assertEquals(listOf("load te", "close te"), log.toList())
    }

    @Test fun prewarmLoadsWithoutAPressAndOnlyOnce() {
        val engine = engine()
        engine.prewarm()
        engine.prewarm()
        assertEquals(1, queued.size)
        runQueued()
        assertEquals(ModelLoad.READY, engine.modelLoad)
        engine.prewarm()
        assertTrue(queued.isEmpty())
        engine.close()
    }

    @Test fun finishBeforeTheModelIsReadyNeverDecodes() {
        val engine = engine()
        engine.begin()
        engine.accept(ShortArray(1600), 0, 1600)
        val result = engine.finish()
        assertEquals(SpeechResult.Failure.NO_MODEL, result.failure)
        assertEquals(1600L, result.audioSamples)
        runQueued()
        engine.close()
    }

    @Test fun oneCtcModelIsResidentAtATime() {
        val te = engine("te", loader = ::thread)
        te.prewarm()
        waitFor("te loaded") { te.modelLoad == ModelLoad.READY }
        val ta = engine("ta", loader = ::thread)
        ta.prewarm()
        Thread.sleep(100)
        assertEquals("ta waits for te's model to be freed", listOf("load te"), log.toList())
        assertEquals(ModelLoad.LOADING, ta.modelLoad)
        te.close()
        waitFor("ta loaded") { ta.modelLoad == ModelLoad.READY }
        assertEquals(listOf("load te", "close te", "load ta"), log.toList())
        ta.close()
        assertEquals(1, residency.availablePermits())
    }

    @Test fun closingDuringTheLoadFreesTheModelOnceItLoaded() {
        gate = CountDownLatch(1)
        val engine = engine(loader = ::thread)
        engine.prewarm()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        engine.close()
        assertTrue(log.isEmpty())
        gate!!.countDown()
        waitFor("model loaded, then freed") { log.size == 2 && residency.availablePermits() == 1 }
        assertEquals(listOf("load te", "close te"), log.toList())
        engine.prewarm()
        assertEquals("a closed engine never loads again", 2, log.size)
    }

    @Test fun closingBeforeTheLoadStartsNeverLoads() {
        residency.acquire()
        val engine = engine(loader = ::thread)
        engine.prewarm()
        engine.close()
        residency.release()
        waitFor("slot free again") { residency.availablePermits() == 1 }
        Thread.sleep(50)
        assertTrue(log.isEmpty())
        assertEquals(1, residency.availablePermits())
    }

    @Test fun aFailedLoadIsFailedReleasesTheSlotAndFinishSaysSo() {
        failLoad = true
        val engine = engine()
        engine.begin()
        runQueued()
        assertEquals(ModelLoad.FAILED, engine.modelLoad)
        assertEquals(1, residency.availablePermits())
        assertEquals(SpeechResult.Failure.NO_MODEL, engine.finish().failure)
        engine.begin()
        assertTrue("no retry loop: the session shows voice off", queued.isEmpty())
        engine.close()
    }

    @Test fun aSlotThatIsNeverFreedFailsTheLoadInsteadOfHanging() {
        residency.acquire()
        val engine = engine(waitMs = 50)
        engine.prewarm()
        runQueued()
        assertEquals(ModelLoad.FAILED, engine.modelLoad)
        assertTrue(decoders.isEmpty())
        residency.release()
    }
}
