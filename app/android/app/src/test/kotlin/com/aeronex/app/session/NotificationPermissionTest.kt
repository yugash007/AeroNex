package com.aeronex.app.session

import com.aeronex.app.session.NotificationPermission.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionTest {
    private var granted = false
    private var asked = false
    private var requests = 0
    private val answers = ArrayList<State>()

    private fun permission(needed: Boolean = true) = NotificationPermission(
        needed = needed,
        granted = { granted },
        asked = { asked },
        markAsked = { asked = true },
        request = { requests++ },
    )

    @Test fun beforeApi33OrWhenGrantedNothingIsAsked() {
        permission(needed = false).query(ask = true, answer = answers::add)
        granted = true
        permission().query(ask = true, answer = answers::add)
        assertEquals(listOf(State.GRANTED, State.GRANTED), answers)
        assertEquals(0, requests)
        assertEquals(false, asked)
    }

    @Test fun aQueryWithoutAskNeverOpensTheSystemDialog() {
        val gate = permission()
        gate.query(ask = false, answer = answers::add)
        assertEquals(listOf(State.ASKABLE), answers)
        assertEquals(0, requests)
        assertEquals(false, asked)
    }

    @Test fun askedOnceAndRememberedTheAnswerComesFromTheDialog() {
        val gate = permission()
        gate.query(ask = true, answer = answers::add)
        assertEquals(1, requests)
        assertTrue("remembered before the dialog shows", asked)
        assertTrue("no answer until the operator answers", answers.isEmpty())
        granted = true
        gate.onResult()
        assertEquals(listOf(State.GRANTED), answers)
        gate.onResult() // a late second result answers nobody
        assertEquals(1, answers.size)
    }

    @Test fun aDenialIsNeverAskedAgainEvenAfterARestart() {
        permission().apply {
            query(ask = true, answer = answers::add)
            onResult()
        }
        assertEquals(listOf(State.DENIED), answers)
        permission().query(ask = true, answer = answers::add) // a new activity, same saved flag
        assertEquals(listOf(State.DENIED, State.DENIED), answers)
        assertEquals(1, requests)
    }

    @Test fun aSecondAskWhileTheDialogIsOpenAnswersAtOnce() {
        val gate = permission()
        gate.query(ask = true, answer = answers::add)
        gate.query(ask = true, answer = answers::add)
        assertEquals(listOf(State.DENIED), answers)
        assertEquals(1, requests)
        gate.onResult() // e.g. the activity went away: the first caller still gets an answer
        assertEquals(listOf(State.DENIED, State.DENIED), answers)
    }

    @Test fun theWireNamesAreTheDartEnumNames() {
        assertEquals(listOf("granted", "askable", "denied"), State.entries.map { it.wire })
    }
}
