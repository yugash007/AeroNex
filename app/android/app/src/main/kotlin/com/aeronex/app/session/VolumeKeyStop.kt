package com.aeronex.app.session

/**
 * Volume-Down as a STOP trigger (spec 4.12 trigger 2), for `MainActivity.dispatchKeyEvent` on the main thread.
 *
 * The first ACTION_DOWN of a press decides for the whole press: while a session is [active] it calls [stop] once
 * (which only posts to the supervisor) and the press, its repeats and its ACTION_UP are consumed; otherwise the whole
 * press stays the system volume key. Holding the key sends nothing more: spec 4.12 lets only the on-screen button's
 * 1 s hold override an autopilot or pilot mode ("Volume-Down and the notification never override"), and
 * [com.aeronex.app.safety.StopTrigger.canOverride] enforces the same in the supervisor.
 */
class VolumeKeyStop(private val active: () -> Boolean, private val stop: () -> Unit) {
    private var consuming = false

    /** One KEYCODE_VOLUME_DOWN event; true when it is consumed. */
    fun onVolumeDown(down: Boolean, repeatCount: Int): Boolean {
        if (down && repeatCount == 0) {
            consuming = active()
            if (consuming) stop()
            return consuming
        }
        val consumed = consuming
        if (!down) consuming = false
        return consumed
    }
}
