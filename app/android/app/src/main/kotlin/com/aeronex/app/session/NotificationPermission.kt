package com.aeronex.app.session

/**
 * POST_NOTIFICATIONS (API 33+), which the session notification and its STOP action need (spec 4.1: best-effort, the
 * session runs without it). Asked at most once per install, and only when the client asks for it: Link setup does,
 * before it connects. Never at connect or auto-connect, because the system dialog covers the whole app, STOP included.
 *
 * [needed]: API 33+. [asked]/[markAsked]: the saved "asked once" flag. [request] opens the system dialog; the activity
 * then calls [onResult] with the operator's answer (or when it goes away, so no caller waits forever). Main thread only.
 */
class NotificationPermission(
    private val needed: Boolean,
    private val granted: () -> Boolean,
    private val asked: () -> Boolean,
    private val markAsked: () -> Unit,
    private val request: () -> Unit,
) {
    /** The `notificationPermission` answer; [wire] is the Dart enum name. */
    enum class State(val wire: String) { GRANTED("granted"), ASKABLE("askable"), DENIED("denied") }

    private var waiting: ((State) -> Unit)? = null

    fun state(): State = when {
        !needed || granted() -> State.GRANTED
        asked() -> State.DENIED
        else -> State.ASKABLE
    }

    /** Answers the state now; with [ask] and never asked before, opens the dialog first and answers after it. */
    fun query(ask: Boolean, answer: (State) -> Unit) {
        val now = state()
        if (!ask || now != State.ASKABLE || waiting != null) {
            answer(now)
            return
        }
        markAsked()
        waiting = answer
        request()
    }

    fun onResult() {
        val answer = waiting ?: return
        waiting = null
        answer(state())
    }
}
