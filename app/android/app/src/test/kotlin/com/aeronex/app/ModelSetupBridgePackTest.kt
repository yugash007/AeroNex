package com.aeronex.app

import android.app.Activity
import android.os.Looper
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.DeviceTier
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.ModelPackStore
import com.aeronex.app.speech.PackEngine
import com.aeronex.app.speech.PackFile
import com.aeronex.app.speech.SpeechModelPack
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Models screen's voice pack methods on `aeronex/inference`: list + status, download with progress, cancel (0.4.0). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ModelSetupBridgePackTest {
    private val gib = 1L shl 30
    private val temp = Files.createTempDirectory("aeronex-bridge-packs").toFile()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun bytes(packId: String, name: String) = "$packId/$name ".repeat(2000).toByteArray()
    private fun file(packId: String, name: String, role: String, url: Boolean = true) = bytes(packId, name).let {
        PackFile(name, role, it.size.toLong(), sha(it), if (url) "https://example.test/$packId/$name" else null)
    }

    private fun ctc(language: String) = "indicconformer-$language-ctc-int8".let { id ->
        SpeechModelPack(id, PackEngine.NEMO_CTC, language, false, DeviceTier.M, null, "MIT", true, 1, true,
            listOf(file(id, "model.int8.onnx", "model"), file(id, "tokens.txt", "tokens")))
    }

    private val kws = "kws-gigaspeech-3.3M-chunk-16".let { id ->
        SpeechModelPack(id, PackEngine.KWS, "en", true, DeviceTier.L, null, "Apache-2.0", true, 1, true,
            listOf("encoder", "decoder", "joiner", "tokens").map { file(id, "$it.onnx", it) })
    }
    private val zhEn = "kws-zh-en-3M-chunk-8".let { id ->
        SpeechModelPack(id, PackEngine.KWS, "en", true, DeviceTier.L, null, "UNCONFIRMED", false, 1, false,
            listOf("encoder", "decoder", "joiner", "tokens").map { file(id, "$it.onnx", it, url = false) })
    }
    private val packs = listOf(ctc("te"), ctc("ta"), ctc("kn"), ctc("ml"), kws, zhEn)

    /** The fake HTTPS server: [gate] holds the stream after its first 4 KiB; [corrupt] flips a byte of every model. */
    @Volatile private var gate: CountDownLatch? = null
    private val firstChunk = CountDownLatch(1)
    @Volatile private var corrupt = false
    private val store = ModelPackStore(File(temp, "speech-packs"), File(temp, "import"), ModelPackStore.installerThread()) {
            target, profile, cancelled, progress ->
        val (packId, name) = profile.url.removePrefix("https://example.test/").split('/')
        val body = bytes(packId, name).let { if (corrupt && name.startsWith("model")) it.copyOf().also { b -> b[1] = 'X'.code.toByte() } else it }
        target.install(profile, object : InputStream() {
            private var position = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= body.size) return -1
                if (position > 0) {
                    firstChunk.countDown()
                    gate?.await(5, TimeUnit.SECONDS)
                }
                val n = minOf(length, 4096, body.size - position)
                System.arraycopy(body, position, buffer, offset, n)
                position += n
                return n
            }
        }, cancelled, progress)
    }

    private lateinit var activity: Activity
    private lateinit var messenger: TestMessenger
    private lateinit var bridge: ModelSetupBridge
    private var gb = 4.0
    private var metered = true

    @Before fun prepare() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        messenger = TestMessenger()
        bridge = bridge()
    }

    private fun bridge() = ModelSetupBridge(activity, messenger,
        packs = { packs },
        packStore = { store },
        policy = { EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (gb * gib).toLong(), false, 8)) },
        metered = { metered })

    @After fun finish() {
        gate?.countDown()
        bridge.close()
        temp.deleteRecursively()
    }

    @Suppress("UNCHECKED_CAST")
    private fun status() = call("packs") as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun pack(id: String) = (status()["packs"] as List<Map<String, Any?>>).single { it["id"] == id }

    @Test fun theListHasTheFourIndicConformerPacksAndTheConfirmedSpotterWithSizes() {
        val status = status()
        assertEquals("M", status["tier"])
        assertEquals(true, status["metered"])
        @Suppress("UNCHECKED_CAST")
        val listed = status["packs"] as List<Map<String, Any?>>
        assertEquals("never the unconfirmed zh-en", listOf("indicconformer-te-ctc-int8", "indicconformer-ta-ctc-int8",
            "indicconformer-kn-ctc-int8", "indicconformer-ml-ctc-int8", "kws-gigaspeech-3.3M-chunk-16"), listed.map { it["id"] })
        val ta = listed[1]
        assertEquals("ctc", ta["kind"])
        assertEquals("ta", ta["language"])
        assertEquals(ctc("ta").bytes, ta["bytes"])
        assertEquals("missing", ta["state"])
        assertEquals(true, ta["usable"])
        assertNull(ta["reason"])
        assertEquals(EnginePolicy.NOTE_ENGLISH_STOP, ta["note"])
        assertEquals(true, ta["downloadable"])
        assertNull(ta["install"])
        assertNull(ta["error"])
        assertEquals("kws", listed[4]["kind"])
    }

    @Test fun aPhoneThatCannotRunAPackSaysWhy() {
        gb = 2.0
        bridge.close()
        bridge = bridge()
        assertEquals("L", status()["tier"])
        assertEquals(false, pack("indicconformer-ta-ctc-int8")["usable"])
        assertEquals(EnginePolicy.PACK_NEEDS_TIER_M, pack("indicconformer-ta-ctc-int8")["reason"])
        assertEquals(EnginePolicy.PACK_TELUGU_USES_VOSK, pack("indicconformer-te-ctc-int8")["reason"])
        val refused = assertThrows(ExecutionException::class.java) {
            call("downloadPack", mapOf("id" to "indicconformer-ta-ctc-int8"))
        }.cause as FlutterException
        assertEquals("PACK_SETUP", refused.code)
        assertEquals(EnginePolicy.PACK_NEEDS_TIER_M, refused.message)
        assertNull(pack("indicconformer-ta-ctc-int8")["install"])
    }

    @Test fun aDownloadShowsProgressThenInstalled() {
        gate = CountDownLatch(1)
        val reply = messenger.call("downloadPack", mapOf("id" to "indicconformer-kn-ctc-int8"))
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        val install = pack("indicconformer-kn-ctc-int8")["install"] as Map<String, Any?>
        assertEquals("download", install["source"])
        assertEquals(ctc("kn").bytes, install["totalBytes"])
        assertTrue(install.toString(), (install["receivedBytes"] as Long) in 1 until ctc("kn").bytes)
        gate!!.countDown()
        assertNull(await(reply))
        val kn = pack("indicconformer-kn-ctc-int8")
        assertEquals("ready", kn["state"])
        assertNull(kn["install"])
        assertNull(kn["error"])
    }

    @Test fun cancelStopsTheDownloadAndNothingIsInstalled() {
        gate = CountDownLatch(1)
        val reply = messenger.call("downloadPack", mapOf("id" to "kws-gigaspeech-3.3M-chunk-16"))
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS))
        assertNull(call("cancelPack", mapOf("id" to "kws-gigaspeech-3.3M-chunk-16")))
        gate!!.countDown()
        val cancelled = assertThrows(ExecutionException::class.java) { await(reply) }.cause as FlutterException
        assertEquals("CANCELLED", cancelled.code)
        val pack = pack("kws-gigaspeech-3.3M-chunk-16")
        assertEquals("missing", pack["state"])
        assertNull(pack["install"])
        assertNull("a cancel is not an error", pack["error"])
    }

    @Test fun aDownloadThatFailsVerificationIsReportedAndStaysOnTheRow() {
        corrupt = true
        val failed = assertThrows(ExecutionException::class.java) {
            call("downloadPack", mapOf("id" to "indicconformer-ml-ctc-int8"))
        }.cause as FlutterException
        assertEquals("PACK_SETUP", failed.code)
        assertTrue(failed.message!!, failed.message!!.contains("does not match"))
        val ml = pack("indicconformer-ml-ctc-int8")
        assertFalse(ml["state"] == "ready")
        assertEquals(failed.message, ml["error"])
    }

    @Test fun unknownOrUnlistedPacksAreRefused() {
        for (id in listOf("kws-zh-en-3M-chunk-8", "lfm-q4", null)) {
            val refused = assertThrows(ExecutionException::class.java) {
                call("downloadPack", mapOf("id" to id))
            }.cause as FlutterException
            assertEquals("PACK_SETUP", refused.code)
        }
        assertNull("cancelling nothing is fine", call("cancelPack", mapOf("id" to "indicconformer-te-ctc-int8")))
    }

    /** Leaving the screen (the activity's onStop cancel) stops Vosk transfers only; a voice pack download keeps going. */
    @Test fun aPackDownloadSurvivesTheScreenGoingAway() {
        gate = CountDownLatch(1)
        messenger.call("downloadPack", mapOf("id" to "indicconformer-te-ctc-int8"))
        assertTrue(firstChunk.await(5, TimeUnit.SECONDS))
        bridge.cancel()
        bridge.close()
        bridge = bridge()
        assertTrue("still visible to a new screen", pack("indicconformer-te-ctc-int8")["install"] != null)
        gate!!.countDown()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (pack("indicconformer-te-ctc-int8")["state"] != "ready") {
            check(System.nanoTime() < deadline) { "download did not finish" }
            Thread.sleep(10)
        }
    }

    private fun call(method: String, args: Any? = null): Any? = await(messenger.call(method, args))

    private fun await(result: CompletableFuture<Any?>): Any? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!result.isDone && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result.get(1, TimeUnit.SECONDS)
    }

    private class TestMessenger : BinaryMessenger {
        private var handler: BinaryMessenger.BinaryMessageHandler? = null
        override fun send(channel: String, message: ByteBuffer?) = Unit
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = Unit
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            assertEquals("aeronex/inference", channel)
            this.handler = handler
        }
        fun call(method: String, args: Any?): CompletableFuture<Any?> {
            val future = CompletableFuture<Any?>()
            val codec = StandardMethodCodec.INSTANCE
            val request = codec.encodeMethodCall(MethodCall(method, args)).apply { flip() }
            handler!!.onMessage(request) { response ->
                if (response == null) future.complete(null)
                else try { future.complete(codec.decodeEnvelope(response.apply { flip() })) }
                catch (error: FlutterException) { future.completeExceptionally(error) }
            }
            return future
        }
    }
}
