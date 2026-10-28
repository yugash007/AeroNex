package com.aeronex.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.session.NotificationPermission
import com.aeronex.app.session.SessionChannel
import com.aeronex.app.session.SessionController
import com.aeronex.app.session.SessionHub
import com.aeronex.app.session.VolumeKeyPtt
import com.aeronex.app.session.VolumeKeyStop
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EnginePolicy
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var models: ModelSetupBridge? = null
    private var session: SessionChannel? = null
    // Asked once per install, only when Link setup asks before a connect: never at connect, where the system dialog
    // would cover STOP (spec 4.1). The answer is the operator's; connecting works either way.
    private val notifications = NotificationPermission(
        needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
        granted = { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
        asked = { getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(NOTIFICATIONS_ASKED, false) },
        markAsked = { getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(NOTIFICATIONS_ASKED, true).apply() },
        request = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST) },
    )
    // RECORD_AUDIO for voice in the session service: asked at most once per install, only when the Flight screen
    // asks (before the first push-to-talk, with its reason, never while the vehicle is armed: the dialog covers STOP).
    private val microphone = NotificationPermission(
        needed = true,
        granted = { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
        asked = { getSharedPreferences(PREFERENCES, MODE_PRIVATE).getBoolean(MICROPHONE_ASKED, false) },
        markAsked = { getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().putBoolean(MICROPHONE_ASKED, true).apply() },
        request = { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST) },
    )
    // Volume-Down is STOP while a session is active (spec 4.12); it only posts to the supervisor and ends PTT.
    private val volumeStop = VolumeKeyStop(
        active = { SessionHub.existing()?.active == true },
        stop = { SessionHub.existing()?.stop(StopTrigger.VOLUME_DOWN) },
    )
    private var visible = false
    // Volume-Up is hold-to-talk while a session is active and the Flight screen is in front (spec 4.4).
    private val volumePtt = VolumeKeyPtt(
        enabled = { visible && SessionHub.existing()?.let { it.active && it.flightScreenVisible } == true },
        down = { SessionHub.existing()?.pttDown() },
        up = { SessionHub.existing()?.pttUp() },
    )

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Speech packs for the Models screen. The v0.2.0 aeronex/local (Android recognizer) and aeronex/mavlink
        // channels are gone: speech and the link run in the session service (spec 4.20).
        models = ModelSetupBridge(this, flutterEngine.dartExecutor.binaryMessenger)
        session = SessionChannel(flutterEngine.dartExecutor.binaryMessenger, applicationContext,
            SessionHub.controller(this), snapshotPeriodMs(),
            notificationPermission = { ask, answer -> notifications.query(ask) { answer(it.wire) } },
            microphonePermission = { ask, answer -> microphone.query(ask) { answer(it.wire) } })
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        session?.dispose()
        session = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    /**
     * Volume keys reach the session before Flutter sees them (spec 4.1): Volume-Down is STOP (and ends PTT),
     * Volume-Up is hold-to-talk on the Flight screen.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action != KeyEvent.ACTION_UP
        val repeat = if (event.action == KeyEvent.ACTION_DOWN) event.repeatCount else maxOf(1, event.repeatCount)
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeStop.onVolumeDown(down, repeat)) return true
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && volumePtt.onVolumeUp(down, repeat)) return true
        return super.dispatchKeyEvent(event)
    }

    /** 5 Hz snapshots on Tier L or the Go flag's strict profile, else 10 Hz (spec 4.1, 6.1: [EnginePolicy]). */
    private fun snapshotPeriodMs(): Long = EnginePolicy(DeviceFacts.read(this)).snapshotPeriodMs

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) notifications.onResult()
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            microphone.onResult()
            // Granted while a session runs: the service adds the microphone type and starts voice now (app visible).
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                SessionHub.existing()?.takeIf { it.active }?.microphoneNeeded?.invoke()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (models?.activityResult(requestCode, resultCode, data) != true) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        // A confirmation is invalidated by backgrounding (S2), and an utterance in progress is dropped (its release
        // may never arrive); the session itself keeps running in the service.
        SessionHub.existing()?.invalidatePending(SessionController.BACKGROUNDED)
        SessionHub.existing()?.voice?.cancelPtt("The app went to the background")
        models?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        models?.close()
        notifications.onResult() // nobody waits for a dialog that will not answer
        microphone.onResult()
        super.onDestroy()
    }

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 42
        const val MICROPHONE_PERMISSION_REQUEST = 43
        const val PREFERENCES = "aeronex_session"
        const val NOTIFICATIONS_ASKED = "notificationsAsked"
        const val MICROPHONE_ASKED = "microphoneAsked"
    }
}
