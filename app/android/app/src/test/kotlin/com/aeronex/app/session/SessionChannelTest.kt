package com.aeronex.app.session

import android.app.Application
import android.os.Looper
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The real channel over Flutter's StandardMethodCodec: threading, replies, events and the service start/stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
@Suppress("UNCHECKED_CAST")
class SessionChannelTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val vehicle = FakeArduCopter()
    private val controller = SessionController(transports = { TransportLease(vehicle.transport()) }, timingFor = { FAST_TIMING })
    private val messenger = TestMessenger()
    private val notificationQueries = CopyOnWriteArrayList<Boolean>()
    private var notificationAnswer: ((String) -> Unit)? = null
    private val microphoneQueries = CopyOnWriteArrayList<Boolean>()
    private val channel = SessionChannel(messenger, app, controller, snapshotPeriodMs = 50,
        notificationPermission = { ask, answer ->
            notificationQueries += ask
            if (ask) notificationAnswer = answer else answer("askable")
        },
        microphonePermission = { ask, answer ->
            microphoneQueries += ask
            answer(if (ask) "granted" else "askable")
        })
    private val udp = mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0)

    @After fun tearDown() {
        channel.dispose()
        controller.close()
        vehicle.close()
    }

    /** Runs the main looper (where replies are posted) until [future] completes. */
    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long = 5_000): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!future.isDone) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadline) fail("no reply within $timeoutMs ms")
            Thread.sleep(5)
        }
        return future.get()
    }

    private fun call(method: String, arguments: Any? = null) = await(messenger.call(method, arguments))

    @Test fun callsReplyThroughTheCodecAndConnectStartsTheService() {
        val snapshot = call("connect", udp) as Map<String, Any?>
        assertEquals(1, snapshot["schemaVersion"])
        assertTrue("connect never opens the permission dialog over STOP", notificationQueries.isEmpty())
        val started = shadowOf(app).nextStartedService
        assertEquals(FlightSessionService::class.java.name, started.component!!.className)
        assertEquals(FlightSessionService.ACTION_START, started.action)
        assertEquals("udp", ((call("getSnapshot") as Map<String, Any?>)["link"] as Map<String, Any?>)["transport"])
        assertEquals(mapOf("enabled" to true), call("setExecution", true))
        assertEquals(null, call("disconnect"))
        assertEquals(FlightSessionService::class.java.name, shadowOf(app).nextStoppedService.component!!.className)
    }

    @Test fun notificationPermissionIsAskedOnlyWhenTheClientAsksAndAnsweredWhenTheOperatorIs() {
        assertEquals("askable", call("notificationPermission", mapOf("ask" to false)))
        val asked = messenger.call("notificationPermission", mapOf("ask" to true))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("waits for the system dialog", asked.isDone)
        notificationAnswer!!("denied")
        assertEquals("denied", await(asked))
        assertEquals(listOf(false, true), notificationQueries)
    }

    @Test fun stopAnswersOnTheMainThreadWithoutTheWorker() {
        val reply = messenger.call("stop", mapOf("heldOneSecond" to false))
        assertTrue("answered before the looper ran", reply.isDone)
        assertEquals(null, reply.get())
        assertTrue(messenger.call("cancel", null).isDone)
    }

    @Test fun submitTextIsParsedInTheServiceAndAnswersFromTheWorker() {
        val reply = messenger.call("submitText", mapOf("text" to "take off", "lang" to "en"))
        assertFalse("answered on the worker, not the main thread", reply.isDone)
        assertEquals(mapOf("error" to "To what height? Add the altitude, for example: take off to 3 meters."), await(reply))
    }

    @Test fun theMicrophonePermissionIsTheActivitysAnswer() {
        assertEquals("askable", call("microphonePermission", mapOf("ask" to false)))
        assertEquals("granted", call("microphonePermission", mapOf("ask" to true)))
        assertEquals(listOf(false, true), microphoneQueries)
    }

    @Test fun unknownMethodsAreNotImplementedAndErrorsArePlatformErrors() {
        try {
            await(messenger.call("selfDestruct", null))
            fail("implemented")
        } catch (e: ExecutionException) {
            assertEquals("not implemented", e.cause?.message)
        }
        // T12: push-to-talk answers at once on the main thread.
        val ptt = messenger.call("pttDown", null)
        assertTrue(ptt.isDone)
        assertEquals(null, ptt.get())
        assertEquals(null, messenger.call("pttUp", null).get())
        try {
            call("connect", udp + ("transport" to "carrier pigeon"))
            fail("connected")
        } catch (e: ExecutionException) {
            val error = e.cause as FlutterException
            assertEquals(SessionException.BAD_ARGS, error.code)
            assertEquals(SessionConfig.BAD_TRANSPORT, error.message)
        }
    }

    @Test fun snapshotsStreamWhileListenedAndStopAfterCancel() {
        messenger.listen()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (messenger.events.size < 3 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("snapshots arrive: ${messenger.events.size}", messenger.events.size >= 3)
        val seqs = messenger.events.map { (it["seq"] as Number).toLong() }
        assertEquals(seqs.sorted(), seqs)
        messenger.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        val count = messenger.events.size
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(count, messenger.events.size)
    }

    /** One Dart side for both channels: method calls with replies, and the event stream's envelopes. */
    private class TestMessenger : BinaryMessenger {
        private val codec = StandardMethodCodec.INSTANCE
        private val handlers = HashMap<String, BinaryMessenger.BinaryMessageHandler?>()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()

        override fun send(channel: String, message: ByteBuffer?) = send(channel, message, null)

        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) {
            if (channel == SessionChannel.EVENTS && message != null) {
                @Suppress("UNCHECKED_CAST")
                events += codec.decodeEnvelope(message.apply { flip() }) as Map<String, Any?>
            }
            callback?.reply(null)
        }

        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            handlers[channel] = handler
        }

        fun call(method: String, arguments: Any?): CompletableFuture<Any?> = invoke(SessionChannel.METHODS, method, arguments)

        fun listen() = invoke(SessionChannel.EVENTS, "listen", null)

        fun cancel() = invoke(SessionChannel.EVENTS, "cancel", null)

        private fun invoke(channel: String, method: String, arguments: Any?): CompletableFuture<Any?> {
            val future = CompletableFuture<Any?>()
            val request = codec.encodeMethodCall(MethodCall(method, arguments)).apply { flip() }
            handlers[channel]!!.onMessage(request) { response ->
                if (response == null) {
                    future.completeExceptionally(IllegalStateException("not implemented"))
                    return@onMessage
                }
                try {
                    future.complete(codec.decodeEnvelope(response.apply { flip() }))
                } catch (error: FlutterException) {
                    future.completeExceptionally(error)
                }
            }
            return future
        }
    }
}
