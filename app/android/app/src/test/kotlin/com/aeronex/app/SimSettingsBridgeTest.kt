package com.aeronex.app

import android.app.Activity
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.FlutterException
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.StandardMethodCodec
import java.nio.ByteBuffer
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
import org.robolectric.annotation.Config

/** Saved simulator endpoint over the real platform channel and SharedPreferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SimSettingsBridgeTest {
    private val defaults = mapOf("enabled" to false, "host" to "192.168.1.100", "remotePort" to 14550, "localPort" to 14551)
    private val valid = mapOf("enabled" to true, "host" to "10.0.2.2", "remotePort" to 14560, "localPort" to 14561)
    private lateinit var activity: Activity
    private lateinit var messenger: TestMessenger
    private lateinit var bridge: ModelSetupBridge

    @Before fun prepare() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        prefs().edit().clear().commit()
        messenger = TestMessenger()
        bridge = ModelSetupBridge(activity, messenger)
    }

    @After fun finish() = bridge.close()

    private fun prefs() = activity.getSharedPreferences("models", 0)

    private fun call(method: String, args: Any? = null): Any? =
        messenger.call(method, args).get(5, TimeUnit.SECONDS)

    @Test fun defaultsBeforeAnythingIsSaved() {
        assertEquals(defaults, call("simSettings"))
    }

    @Test fun savedSettingsSurviveARestartUnderTheDocumentedKeys() {
        assertNull(call("saveSimSettings", valid))
        bridge.close()
        bridge = ModelSetupBridge(activity, messenger)
        assertEquals(valid, call("simSettings"))
        assertEquals(true, prefs().getBoolean("sim-enabled", false))
        assertEquals("10.0.2.2", prefs().getString("sim-host", null))
        assertEquals(14560, prefs().getInt("sim-remote-port", 0))
        assertEquals(14561, prefs().getInt("sim-local-port", 0))
    }

    @Test fun invalidSettingsAreRejectedAndNothingIsStored() {
        val invalid = listOf(
            valid + ("host" to "sitl.local"), valid + ("host" to "256.1.1.1"), valid + ("host" to "1.2.3"),
            valid + ("host" to "192.168.001.100"), valid + ("host" to " 10.0.2.2"), valid + ("host" to ""),
            valid + ("host" to "::1"), valid + ("host" to 7),
            valid + ("remotePort" to 0), valid + ("remotePort" to 65536), valid + ("localPort" to "14551"),
            valid + ("localPort" to (1L shl 40)), valid + ("enabled" to "true"), valid - "localPort", null, listOf(1))
        for (args in invalid) {
            val failure = assertThrows("$args", ExecutionException::class.java) {
                call("saveSimSettings", args)
            }.cause as FlutterException
            assertEquals("INVALID_SIM_SETTINGS", failure.code)
        }
        assertTrue(prefs().all.keys.none { it.startsWith("sim-") })
        assertEquals(defaults, call("simSettings"))
    }

    @Test fun storedValuesOfTheWrongTypeFallBackToDefaults() {
        prefs().edit().putString("sim-enabled", "yes").putInt("sim-host", 4)
            .putString("sim-remote-port", "14560").putLong("sim-local-port", 14561L).commit()
        assertEquals(defaults, call("simSettings"))
    }

    private class TestMessenger : BinaryMessenger {
        private var handler: BinaryMessenger.BinaryMessageHandler? = null
        override fun send(channel: String, message: ByteBuffer?) = Unit
        override fun send(channel: String, message: ByteBuffer?, callback: BinaryMessenger.BinaryReply?) = Unit
        override fun setMessageHandler(channel: String, handler: BinaryMessenger.BinaryMessageHandler?) {
            this.handler = handler
        }
        fun call(method: String, args: Any?): CompletableFuture<Any?> {
            val future = CompletableFuture<Any?>()
            val codec = StandardMethodCodec.INSTANCE
            val request = codec.encodeMethodCall(MethodCall(method, args)).apply { flip() }
            handler!!.onMessage(request) { response ->
                try { future.complete(codec.decodeEnvelope(response!!.apply { flip() })) }
                catch (error: FlutterException) { future.completeExceptionally(error) }
            }
            return future
        }
    }
}
