package com.aeronex.app.session

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.aeronex.app.R
import com.aeronex.app.safety.StopTrigger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The foreground service (type `connectedDevice|microphone`, spec 4.1, Phase 1b) that keeps the process, and so the
 * link threads, the 1 Hz GCS heartbeat, the STOP lane and the microphone with the voice stop spotter, alive while the
 * app is in the background (S7). It owns no flight state: the
 * [SessionController] in [SessionHub] does. Started after a successful `connect`, stopped on `disconnect`; never
 * restarted by the system (START_NOT_STICKY: a killed process is link loss and nothing auto-resumes).
 *
 * Its notification shows the link and mode and has a STOP action, which calls the controller's STOP (trigger
 * NOTIFICATION, never an override) on the main thread and only posts to the supervisor. On API 33+ it is visible only
 * with POST_NOTIFICATIONS, and from Android 14 the user can dismiss it, so it is best-effort and never the only STOP.
 * If Android refuses the foreground start, the service logs it and keeps following the session without the flag
 * (`link.foregroundService` false): the session itself lives in [SessionHub] and runs while the app is visible.
 *
 * It also sounds the STOP alarm ([StopAlarmSound]) while the supervisor's STOP alarm is raised, in the background too.
 *
 * Microphone (T12): Android 14+ lets a microphone-type service start only from the foreground with RECORD_AUDIO
 * granted. So the service enters the foreground as `connectedDevice` alone while the microphone is not allowed, and
 * adds `microphone` ([ACTION_MICROPHONE], sent when push-to-talk is first used after the grant, from the visible
 * app) with a second `startForeground`. Voice ([SessionController.startVoice]) runs from then until the service ends.
 */
class FlightSessionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var controller: SessionController
    private lateinit var alarm: StopAlarmSound
    /** Following the session: change listener, refresh tick and alarm (with or without the foreground flag). */
    private var running = false
    private var foreground = false
    private var microphone = false
    private var shown: String? = null
    private var shownAtMs = 0L
    private val refreshQueued = AtomicBoolean(false)
    private val refreshNow = Runnable {
        refreshQueued.set(false)
        refresh()
    }
    private val onChange: () -> Unit = { if (refreshQueued.compareAndSet(false, true)) handler.post(refreshNow) }
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = SessionHub.controller(this)
        alarm = StopAlarmSound(ToneGeneratorTone(), { task, delayMs -> handler.postDelayed(task, delayMs) },
            handler::removeCallbacks)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Flight session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the vehicle link and STOP running while the app is in the background."
                setShowBadge(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) controller.stop(StopTrigger.NOTIFICATION)
        if (!running) {
            if (intent?.action != ACTION_START && !controller.active) {
                stopSelf(startId) // a STOP from a stale notification: nothing to keep alive
                return START_NOT_STICKY
            }
            follow()
        } else if (intent?.action == ACTION_MICROPHONE) {
            addMicrophone()
        }
        return START_NOT_STICKY
    }

    /** Spec A6: memory is short, so the AI planner frees its model (it loads again on its next use). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) controller.trimMemory(level)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(refreshNow)
        alarm.release()
        controller.stopVoice()
        microphone = false
        if (running) {
            controller.removeChangeListener(onChange)
            controller.foregroundService = false
            running = false
        }
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        super.onDestroy()
    }

    private fun follow() {
        running = true
        val withMicrophone = microphoneAllowed()
        foreground = enterForeground(withMicrophone)
        microphone = foreground && withMicrophone
        controller.foregroundService = foreground
        controller.addChangeListener(onChange)
        handler.postDelayed(tick, REFRESH_MS)
        startVoice(withMicrophone)
        refresh()
    }

    /** Push-to-talk was first used after the microphone was allowed: add the microphone type, then start voice. */
    private fun addMicrophone() {
        val allowed = microphoneAllowed()
        if (allowed && foreground && !microphone) microphone = enterForeground(microphone = true)
        startVoice(allowed)
    }

    /**
     * Voice runs with the microphone permission (with the microphone type when in the foreground; while the app is
     * visible Android lets it record either way). Without it, the status says why.
     */
    private fun startVoice(allowed: Boolean) {
        if (!allowed) {
            controller.stopVoice(VoiceReason.NO_MICROPHONE)
        } else if (foreground && !microphone) {
            // A refused microphone type: voice would stop working in the background, so say so instead.
            controller.stopVoice(VoiceReason.MICROPHONE_BLOCKED)
        } else {
            controller.startVoice()
        }
    }

    private fun microphoneAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** False when Android refused (logged): no crash, the session keeps running without the foreground flag. */
    private fun enterForeground(microphone: Boolean): Boolean {
        val text = currentText()
        val notification = build(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, serviceTypes(microphone))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // E.g. started from the background, or a missing FGS-type prerequisite. The session still runs while the
            // app is visible; the snapshot reports link.foregroundService = false.
            Log.w(TAG, "startForeground refused; the session continues without the foreground service", e)
            return false
        }
        shown = text
        shownAtMs = SystemClock.elapsedRealtime()
        return true
    }

    /**
     * Sounds or silences the STOP alarm; re-posts the notification when the line changed, and every [REPOST_MS] (so a
     * permission granted later shows it).
     */
    private fun refresh() {
        if (!running) return
        alarm.update(controller.supervisorState().stop?.alarm == true)
        if (!foreground) return
        val text = currentText()
        val now = SystemClock.elapsedRealtime()
        if (text == shown && now - shownAtMs < REPOST_MS) return
        shown = text
        shownAtMs = now
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(text))
        } catch (_: RuntimeException) {
            // Best-effort.
        }
    }

    private fun currentText() =
        SessionNotificationText.line(controller.linkInfo(), controller.vehicle(), controller.supervisorState().stop)

    private fun build(text: String): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(this, REQUEST_STOP,
            Intent(this, FlightSessionService::class.java).setAction(ACTION_STOP), flags)
        val open = packageManager.getLaunchIntentForPackage(packageName)
            ?.let { PendingIntent.getActivity(this, REQUEST_OPEN, it, flags) }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(SessionNotificationText.TITLE)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                SessionNotificationText.STOP_ACTION, stop).build())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    companion object {
        const val ACTION_START = "com.aeronex.app.session.START"
        const val ACTION_STOP = "com.aeronex.app.session.STOP"
        /** Add the microphone type and start voice (push-to-talk was used after RECORD_AUDIO was granted). */
        const val ACTION_MICROPHONE = "com.aeronex.app.session.MICROPHONE"
        const val CHANNEL_ID = "aeronex_flight_session"
        const val NOTIFICATION_ID = 7_001
        const val TAG = "AeroNexSession"
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val REFRESH_MS = 1_000L
        private const val REPOST_MS = 15_000L

        /** From the foreground activity after `connect`; false if Android refused to start it. */
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, FlightSessionService::class.java).setAction(ACTION_START))
            true
        } catch (_: RuntimeException) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlightSessionService::class.java))
        }

        /** From the visible app when push-to-talk is used with voice off; ignored when the service is not running. */
        fun addMicrophone(context: Context): Boolean = try {
            context.startService(Intent(context, FlightSessionService::class.java).setAction(ACTION_MICROPHONE))
            true
        } catch (_: RuntimeException) {
            false
        }

        /** The foreground service types: `connectedDevice`, plus `microphone` only with RECORD_AUDIO granted. */
        fun serviceTypes(microphone: Boolean): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
}
