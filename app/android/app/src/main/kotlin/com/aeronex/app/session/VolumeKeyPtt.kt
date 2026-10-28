package com.aeronex.app.session

/**
 * Volume-Up as hold-to-talk (spec 4.4), for `MainActivity.dispatchKeyEvent` on the main thread.
 *
 * The first ACTION_DOWN of a press decides for the whole press: while [enabled] (a session is active and the Flight
 * screen is in front) it calls [down] once, and its repeats and ACTION_UP (which calls [up]) are consumed; otherwise
 * the whole press stays the system volume key. A STOP key during the press ends PTT in the session; the [up] that
 * follows then finds no press and does nothing.
 */
class VolumeKeyPtt(private val enabled: () -> Boolean, private val down: () -> Unit, private val up: () -> Unit) {
    private var talking = false

    /** One KEYCODE_VOLUME_UP event; true when it is consumed. */
    fun onVolumeUp(down: Boolean, repeatCount: Int): Boolean {
        if (down && repeatCount == 0) {
            talking = enabled()
            if (talking) this.down()
            return talking
        }
        val consumed = talking
        if (!down) {
            if (talking) this.up()
            talking = false
        }
        return consumed
    }
}
