package com.aeronex.app.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * The hand-written typed channel (plan deviation: no Pigeon): MethodChannel [METHODS] for the [SessionApi] calls and
 * EventChannel [EVENTS] for [SessionSchema] snapshots every [snapshotPeriodMs] plus an immediate one on any plan,
 * STOP or link change. STOP, Cancel and the alarm acknowledgement answer on the main thread (they only post to the
 * supervisor); every other call runs on one worker thread and only its reply is posted back to the main thread.
 * Snapshots reach the main thread through [LatestOnly], so a stalled UI never builds a backlog.
 *
 * A successful `connect` starts [FlightSessionService] and `disconnect` stops it. Connecting never asks for a
 * permission: `notificationPermission({ask})` answers [NotificationPermission.State.wire] through
 * [notificationPermission] (the activity's [NotificationPermission]), which opens the POST_NOTIFICATIONS dialog only
 * when asked to (Link setup, before it connects) and answers once the operator has. `microphonePermission({ask})`
 * does the same for RECORD_AUDIO through [microphonePermission] (the Flight screen asks before the first push-to-talk,
 * with its reason, and never while the vehicle is armed). [dispose] when the Flutter engine goes away; the session
 * itself keeps running in the service.
 */
class SessionChannel(
    messenger: BinaryMessenger,
    private val context: Context,
    private val controller: SessionController,
    private val snapshotPeriodMs: Long,
    private val notificationPermission: (ask: Boolean, answer: (String) -> Unit) -> Unit = { _, answer -> answer(NotificationPermission.State.GRANTED.wire) },
    private val microphonePermission: (ask: Boolean, answer: (String) -> Unit) -> Unit = { _, answer -> answer(NotificationPermission.State.GRANTED.wire) },
) {
    private val api = SessionApi(controller)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aeronex-session-channel").apply { isDaemon = true }
    }
    private val methods = MethodChannel(messenger, METHODS)
    private val events = EventChannel(messenger, EVENTS)
    @Volatile private var pump: SnapshotPump? = null
    private val poke: () -> Unit = { pump?.poke() }

    init {
        methods.setMethodCallHandler { call, result -> onCall(call.method, call.arguments, result) }
        events.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) = startPump(events)
            override fun onCancel(arguments: Any?) = stopPump()
        })
        controller.addChangeListener(poke)
    }

    fun dispose() {
        methods.setMethodCallHandler(null)
        events.setStreamHandler(null)
        controller.removeChangeListener(poke)
        stopPump()
        worker.shutdown()
    }

    private fun onCall(method: String, arguments: Any?, result: MethodChannel.Result) {
        if (method == NOTIFICATION_PERMISSION || method == MICROPHONE_PERMISSION) {
            val ask = (arguments as? Map<*, *>)?.get("ask") == true
            val permission = if (method == NOTIFICATION_PERMISSION) notificationPermission else microphonePermission
            permission(ask) { state -> result.success(state) }
            return
        }
        if (SessionApi.isInline(method)) {
            send(result, api.call(method, arguments))
            return
        }
        try {
            worker.execute {
                val reply = api.call(method, arguments)
                main.post {
                    if (method == "connect" && reply is SessionApi.Reply.Ok) FlightSessionService.start(context)
                    if (method == "disconnect") FlightSessionService.stop(context)
                    send(result, reply)
                }
            }
        } catch (_: RejectedExecutionException) {
            result.error("CLOSED", "The session channel is closed.", null)
        }
    }

    private fun send(result: MethodChannel.Result, reply: SessionApi.Reply) = when (reply) {
        is SessionApi.Reply.Ok -> result.success(reply.value)
        is SessionApi.Reply.Failed -> result.error(reply.code, reply.message, null)
        SessionApi.Reply.NotImplemented -> result.notImplemented()
    }

    private fun startPump(sink: EventChannel.EventSink) {
        stopPump()
        lateinit var started: SnapshotPump
        val latest = LatestOnly<Map<String, Any?>>(post = { main.post(it) }) { if (pump === started) sink.success(it) }
        started = SnapshotPump(snapshotPeriodMs, controller::snapshot, latest::offer)
        pump = started
        started.start()
    }

    private fun stopPump() {
        pump?.close()
        pump = null
    }

    companion object {
        const val METHODS = "aeronex/session"
        const val EVENTS = "aeronex/session/state"
        /** `{ask}` → "granted" | "askable" | "denied"; answered by the activity, not [SessionApi]. */
        const val NOTIFICATION_PERMISSION = "notificationPermission"
        /** `{ask}` → "granted" | "askable" | "denied" for RECORD_AUDIO; answered by the activity. */
        const val MICROPHONE_PERMISSION = "microphonePermission"
    }
}
