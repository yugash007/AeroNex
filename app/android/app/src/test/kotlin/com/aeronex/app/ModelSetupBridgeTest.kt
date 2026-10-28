package com.aeronex.app

import android.app.Activity
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Models screen's `aeronex/inference` channel over the real platform codec and private model storage (T13). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ModelSetupBridgeTest {
    private val payload = "verified model fixture".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    private val profiles = listOf("vosk-en-in", "vosk-te", "vosk-hi")
        .associateWith { LocalModelProfile("$it.bin", payload.size.toLong(), hash) }
    private lateinit var activity: Activity
    private lateinit var messenger: TestMessenger
    private lateinit var bridge: ModelSetupBridge

    @Before fun prepare() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        modelDir().mkdirs()
        modelDir().listFiles()?.forEach { it.delete() }
        messenger = TestMessenger()
        bridge = ModelSetupBridge(activity, messenger, profiles)
    }

    @After fun finish() = bridge.close()

    private fun modelDir() = File(activity.filesDir, "models")

    private fun install(id: String) = File(modelDir(), profiles.getValue(id).file).writeBytes(payload)

    @Suppress("UNCHECKED_CAST")
    private fun status() = call("status") as Map<String, Any?>

    @Test fun theAppOffersOnlyTheVoskPacks() {
        // LFM, Whisper, Whistle and Needle are gone with their native runtimes (spec 4.20).
        assertEquals(setOf("vosk-en-in", "vosk-te", "vosk-hi"), ModelSetupBridge.PROFILES.keys)
        assertSame(VoskModels.profiles, ModelSetupBridge.PROFILES)
    }

    @Test fun statusReportsVerifiedStatesAndNothingFromTheRemovedEngines() {
        install("vosk-te")
        val status = status()
        assertEquals(setOf("models", "modelStates", "transfer"), status.keys)
        assertEquals(mapOf("vosk-en-in" to false, "vosk-te" to true, "vosk-hi" to false), status["models"])
        assertEquals(mapOf("vosk-en-in" to "missing", "vosk-te" to "ready", "vosk-hi" to "missing"),
            status["modelStates"])
        assertNull(status["transfer"])
    }

    @Test fun installedFilesStayReadyAfterARestart() {
        install("vosk-hi")
        bridge.close()
        bridge = ModelSetupBridge(activity, messenger, profiles)
        assertEquals(true, (status()["models"] as Map<*, *>)["vosk-hi"])
    }

    @Test fun aCorruptOrReplacedFileIsNotReady() {
        install("vosk-en-in")
        assertEquals("ready", (status()["modelStates"] as Map<*, *>)["vosk-en-in"])
        val target = File(modelDir(), profiles.getValue("vosk-en-in").file)
        val replacement = File(modelDir(), "replacement.part")
        replacement.writeBytes(ByteArray(payload.size))
        replacement.setLastModified(target.lastModified())
        Files.move(replacement.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
        assertEquals("invalid", (status()["modelStates"] as Map<*, *>)["vosk-en-in"])
        target.delete()
        assertEquals("missing", (status()["modelStates"] as Map<*, *>)["vosk-en-in"])
    }

    @Test fun removedProfilesCannotBeDownloadedOrImported() {
        for (id in listOf("lfm-q4", "whisper-tiny", "whistle", "needle3")) {
            val download = assertThrows(ExecutionException::class.java) {
                call("downloadModel", mapOf("profile" to id))
            }.cause as FlutterException
            assertEquals("MODEL_SETUP", download.code)
            val import = assertThrows(ExecutionException::class.java) {
                call("importModel", mapOf("profile" to id))
            }.cause as FlutterException
            assertEquals("IMPORT", import.code)
        }
    }

    @Test fun theRemovedSpeechAndInferenceMethodsAreNotImplemented() {
        for (method in listOf("interpret", "whisper", "whistle", "vosk", "selectModels", "configureSpeech",
            "speechStatus", "stopRecording")) {
            assertSame(method, NOT_IMPLEMENTED, call(method, mapOf("text" to "hold", "language" to "en-IN")))
        }
    }

    @Test fun cancelAnswersWhenNothingIsRunning() {
        assertNull(call("cancel"))
        assertEquals(true, (status()["models"] as Map<*, *>).isNotEmpty())
    }

    private fun call(method: String, args: Any? = null): Any? {
        val result = messenger.call(method, args)
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
                if (response == null) future.complete(NOT_IMPLEMENTED)
                else try { future.complete(codec.decodeEnvelope(response.apply { flip() })) }
                catch (error: FlutterException) { future.completeExceptionally(error) }
            }
            return future
        }
    }

    private companion object {
        val NOT_IMPLEMENTED = Any()
    }
}
