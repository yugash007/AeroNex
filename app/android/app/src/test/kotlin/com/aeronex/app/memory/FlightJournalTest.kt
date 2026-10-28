package com.aeronex.app.memory

import com.aeronex.app.vehicle.Ned
import org.junit.Assert.*
import org.junit.Test

/** Spec 2026-10-28 M1: the trail, spots, history and home guard of the flight memory. */
class FlightJournalTest {
    private class RecordingStore(private val initial: MemoryState? = null) : JournalStore {
        val saved = mutableListOf<MemoryState>()
        override fun load() = initial
        override fun save(state: MemoryState) {
            saved += state
        }
    }

    private var now = 1_000L
    private val store = RecordingStore()
    private val journal = FlightJournal(store) { now }

    private fun ned(x: Double, y: Double, z: Double = -3.0) = Ned(x, y, z)

    private fun step(name: String = "move_relative", outcome: String = "COMPLETED") =
        FlightJournal.RecordedStep(name, mapOf("direction" to "left", "distance_m" to 2), "Move left 2 m", "typed", outcome)

    @Test fun aTakeoffStartsANewTrailAndArrivalsExtendIt() {
        journal.arrived(ned(9.0, 9.0), "old flight")
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.arrived(ned(3.0, 0.0), "Move forward 3 m")
        journal.arrived(ned(3.0, 2.0), "Move right 2 m")
        assertEquals(listOf(ned(0.0, 0.0), ned(3.0, 0.0), ned(3.0, 2.0)), journal.state.trail.map { it.position })
        assertEquals(FlightJournal.TAKEOFF_LABEL, journal.state.trail.first().label)
        assertEquals(1_000L, journal.state.trail.first().atWallMs)
    }

    @Test fun anArrivalCloserThanTheSpacingAddsNothingAndSavesNothing() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        val saves = store.saved.size
        journal.arrived(ned(0.3, 0.0), "Hold position")
        assertEquals(1, journal.state.trail.size)
        assertEquals(saves, store.saved.size)
    }

    @Test fun theTrailIsBoundedButAlwaysKeepsTheTakeoffPosition() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        for (i in 1..FlightJournal.TRAIL_LIMIT + 10) journal.arrived(ned(i.toDouble(), 0.0), "step $i")
        val trail = journal.state.trail
        assertEquals(FlightJournal.TRAIL_LIMIT, trail.size)
        assertEquals(ned(0.0, 0.0), trail.first().position)
        assertEquals(ned((FlightJournal.TRAIL_LIMIT + 10).toDouble(), 0.0), trail.last().position)
    }

    @Test fun keepTrailUndoesTheNewestPositions() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.arrived(ned(3.0, 0.0), "a")
        journal.arrived(ned(6.0, 0.0), "b")
        journal.keepTrail(2)
        assertEquals(listOf(ned(0.0, 0.0), ned(3.0, 0.0)), journal.state.trail.map { it.position })
        journal.keepTrail(5)
        assertEquals(2, journal.state.trail.size)
    }

    @Test fun spotsAreSavedByNumberAndReplaced() {
        journal.markPoint(2, ned(1.0, 1.0))
        journal.markPoint(2, ned(4.0, 4.0))
        journal.markPoint(1, ned(2.0, 0.0))
        assertEquals(ned(4.0, 4.0), journal.state.points.getValue(2).position)
        assertEquals("Spot 2", journal.state.points.getValue(2).label)
        assertEquals(setOf(1, 2), journal.state.points.keys)
    }

    @Test fun aMovedHomeForgetsTheTrailAndSpotsButKeepsTheHistory() {
        journal.observeHome(ned(0.0, 0.0, 0.0))
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.markPoint(1, ned(2.0, 2.0))
        journal.recordPlan(listOf(step()))
        journal.observeHome(ned(0.5, 0.0, 0.0)) // within the tolerance: same home
        assertEquals(1, journal.state.trail.size)
        journal.observeHome(ned(40.0, 0.0, 0.0))
        val state = journal.state
        assertEquals(ned(40.0, 0.0, 0.0), state.home)
        assertTrue(state.trail.isEmpty())
        assertTrue(state.points.isEmpty())
        assertEquals(1, state.history.size)
        assertEquals(FlightJournal.HOME_MOVED, state.notice)
    }

    @Test fun theFirstHomeIsAdoptedWithoutForgettingAnything() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.observeHome(ned(0.0, 0.0, 0.0))
        assertEquals(1, journal.state.trail.size)
        assertNull(journal.state.notice)
    }

    @Test fun finishedPlansGoToTheHistoryAsOnePlanAndTheHistoryIsBounded() {
        journal.recordPlan(listOf(step("takeoff"), step(), step("land")))
        val plan = journal.state.history.map { it.planId }.distinct()
        assertEquals(listOf(1L), plan)
        assertEquals(listOf(1L, 2L, 3L), journal.state.history.map { it.id })
        assertEquals(3, journal.plan(1).size)
        repeat(FlightJournal.HISTORY_LIMIT) { journal.recordPlan(listOf(step())) }
        assertEquals(FlightJournal.HISTORY_LIMIT, journal.state.history.size)
        assertTrue(journal.plan(1).isEmpty())
        journal.recordPlan(emptyList())
        assertEquals(FlightJournal.HISTORY_LIMIT + 4L, journal.state.nextId)
    }

    @Test fun clearForgetsEverythingButHome() {
        journal.observeHome(ned(0.0, 0.0, 0.0))
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.markPoint(1, ned(1.0, 0.0))
        journal.recordPlan(listOf(step()))
        journal.clear()
        val state = journal.state
        assertEquals(ned(0.0, 0.0, 0.0), state.home)
        assertTrue(state.trail.isEmpty() && state.points.isEmpty() && state.history.isEmpty())
        assertEquals(2L, state.nextId)
    }

    @Test fun aRestoredMemoryWithoutAHomeKeepsOnlyItsHistory() {
        val saved = MemoryState(trail = listOf(Waypoint(ned(1.0, 1.0), "a", 0)),
            points = mapOf(1 to Waypoint(ned(2.0, 2.0), "Spot 1", 0)),
            history = listOf(CommandRecord(4, 4, 0, "land", emptyMap(), "Land", "typed", "COMPLETED")), nextId = 5)
        val restored = FlightJournal(RecordingStore(saved)).state
        assertTrue(restored.trail.isEmpty() && restored.points.isEmpty())
        assertEquals(saved.history, restored.history)
        assertEquals(5L, restored.nextId)
        val withHome = saved.copy(home = ned(0.0, 0.0, 0.0))
        assertEquals(withHome, FlightJournal(RecordingStore(withHome)).state)
    }

    @Test fun everyChangeIsSavedOnce() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.takeoffCompleted(ned(0.0, 0.0)) // same state: not saved again
        journal.markPoint(1, ned(0.0, 0.0))
        assertEquals(2, store.saved.size)
        assertEquals(journal.state, store.saved.last())
    }
}
