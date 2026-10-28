package com.aeronex.app.session

import android.media.AudioManager
import android.media.ToneGenerator

/** One short alarm burst on the phone's speaker. Best-effort: it never throws. */
interface AlarmTone {
    fun play()

    fun release()
}

/**
 * The audible STOP alarm ("STOP NOT CONFIRMED … use RC" is a loud alarm, spec 4.0 and 4.12). Feed it the supervisor's
 * `stop.alarm` on every state change with [update]: when the alarm is raised [tone] plays at once and then every
 * [periodMs]; it goes quiet when the operator acknowledges the alarm or the supervisor clears it. [FlightSessionService]
 * owns it, so it also sounds while the app is in the background.
 *
 * One thread: call it, and run what [schedule] posts, on the same thread (the service's main thread).
 */
class StopAlarmSound(
    private val tone: AlarmTone,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val periodMs: Long = PERIOD_MS,
) {
    var sounding = false
        private set

    private val repeat = object : Runnable {
        override fun run() {
            if (!sounding) return
            tone.play()
            schedule(this, periodMs)
        }
    }

    fun update(alarm: Boolean) {
        if (alarm == sounding) return
        sounding = alarm
        if (alarm) repeat.run() else unschedule(repeat)
    }

    fun release() {
        update(false)
        tone.release()
    }

    companion object {
        const val PERIOD_MS = 1_000L
    }
}

/** [AlarmTone] on the alarm stream, so it sounds at the alarm volume even when media is muted. */
class ToneGeneratorTone : AlarmTone {
    private var generator: ToneGenerator? = null

    override fun play() {
        try {
            val tones = generator ?: ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME).also { generator = it }
            tones.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, BURST_MS)
        } catch (_: RuntimeException) {
            // No audio now (the hardware is busy or missing): the banner, the notification and the vibration remain.
        }
    }

    override fun release() {
        generator?.release()
        generator = null
    }

    private companion object {
        /** Three short beeps of the emergency pattern, inside one [StopAlarmSound.PERIOD_MS]. */
        const val BURST_MS = 600
    }
}
