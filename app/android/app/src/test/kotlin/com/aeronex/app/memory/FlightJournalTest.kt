package com.aeronex.app.memory

import com.aeronex.app.vehicle.FrameOrigin
import com.aeronex.app.vehicle.Ned
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

/** Spec 2026-10-28 M1: the trail, points, history and home guard of the flight memory. */
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

    /** A frame origin [north] metres north of the first. */
    private fun origin(north: Double = 0.0) = FrameOrigin(-353632621.0 + north / FrameOrigin.LATLON_TO_M, 1491652374.0, 584.0)

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

    @Test fun pointsAreSavedByNumberAndReplaced() {
        journal.markPoint(2, ned(1.0, 1.0))
        journal.markPoint(2, ned(4.0, 4.0))
        journal.markPoint(1, ned(2.0, 0.0))
        assertEquals(ned(4.0, 4.0), journal.state.points.getValue(2).position)
        assertEquals("Point 2", journal.state.points.getValue(2).label)
        assertEquals(setOf(1, 2), journal.state.points.keys)
    }

    @Test fun homeMovingInTheSameFrameKeepsTheTrailAndPoints() {
        // Review R2: ArduCopter sets home where it arms. A second takeoff 9 m away is the same frame, so the points
        // stay and "return home" flies to the new home.
        journal.observeHome(ned(0.0, 0.0, 0.0), origin())
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.markPoint(1, ned(4.0, 0.0))
        journal.observeHome(ned(9.0, 0.0, 0.0), origin())
        val state = journal.state
        assertEquals(ned(9.0, 0.0, 0.0), state.home)
        assertEquals(setOf(1), state.points.keys)
        assertEquals(1, state.trail.size)
        assertNull(state.notice)
    }

    @Test fun aNewFrameForgetsTheTrailAndPointsButKeepsTheHistory() {
        journal.observeHome(ned(0.0, 0.0, 0.0), origin())
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.markPoint(1, ned(2.0, 2.0))
        journal.recordPlan(listOf(step()))
        journal.observeHome(ned(0.0, 0.0, 0.0), origin(north = 0.5)) // within the tolerance: the same frame
        assertEquals(1, journal.state.trail.size)
        // A reboot 40 m away: home is (0, 0, 0) again, in a frame 40 m from the old one.
        journal.observeHome(ned(0.0, 0.0, 0.0), origin(north = 40.0))
        val state = journal.state
        assertEquals(origin(north = 40.0), state.origin)
        assertTrue(state.trail.isEmpty())
        assertTrue(state.points.isEmpty())
        assertEquals(1, state.history.size)
        assertEquals(FlightJournal.FRAME_CHANGED, state.notice)
    }

    @Test fun withoutAnOriginOnlyAHomeThatStayedPutIsTheSameFrame() {
        journal.observeHome(ned(0.0, 0.0, 0.0), null)
        journal.markPoint(1, ned(2.0, 2.0))
        journal.observeHome(ned(0.5, 0.0, 0.0), null)
        assertEquals(setOf(1), journal.state.points.keys)
        journal.observeHome(ned(40.0, 0.0, 0.0), null)
        assertTrue(journal.state.points.isEmpty())
        assertEquals(FlightJournal.FRAME_CHANGED, journal.state.notice)
    }

    @Test fun theFirstHomeIsAdoptedWithoutForgettingAnything() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.observeHome(ned(0.0, 0.0, 0.0), origin())
        assertEquals(1, journal.state.trail.size)
        assertEquals(origin(), journal.state.origin)
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

    @Test fun theHistoryDropsWholePlansNeverTheFirstStepsOfOne() {
        // Review R3: trimmed record by record, the oldest plan lost its takeoff, and "run again" ran the rest of it.
        journal.recordPlan(listOf(step("takeoff"), step(), step("land")))
        repeat(FlightJournal.HISTORY_LIMIT - 2) { journal.recordPlan(listOf(step())) }
        assertTrue("the 3-step plan goes whole", journal.plan(1).isEmpty())
        assertEquals(FlightJournal.HISTORY_LIMIT - 2, journal.state.history.size)
        // A history saved with its oldest plan cut short (before this fix) loses that plan when restored.
        val cut = MemoryState(history = listOf(
            CommandRecord(2, 1, 0, "move_relative", emptyMap(), "Move", "typed", "COMPLETED"),
            CommandRecord(3, 1, 0, "land", emptyMap(), "Land", "typed", "COMPLETED"),
            CommandRecord(4, 4, 0, "land", emptyMap(), "Land", "typed", "COMPLETED")), nextId = 5)
        assertEquals(listOf(4L), FlightJournal(RecordingStore(cut)).state.history.map { it.planId })
    }

    @Test fun savesReachTheStoreInTheOrderTheChangesWereMade() {
        // Review R4: an arrival on the supervisor thread and a clear on the UI thread. Saved outside the lock, the
        // older state could be saved last, and the cleared memory came back after a restart.
        val firstSaving = CountDownLatch(1)
        val otherSaved = CountDownLatch(1)
        val saved = CopyOnWriteArrayList<MemoryState>()
        val racing = FlightJournal(object : JournalStore {
            @Volatile var first = true
            override fun load(): MemoryState? = null
            override fun save(state: MemoryState) {
                if (first) {
                    first = false
                    firstSaving.countDown()
                    otherSaved.await(500, TimeUnit.MILLISECONDS) // the other thread may save meanwhile
                    saved += state
                } else {
                    saved += state
                    otherSaved.countDown()
                }
            }
        }) { now }
        val arrival = thread { racing.takeoffCompleted(ned(0.0, 0.0)) }
        assertTrue(firstSaving.await(5, TimeUnit.SECONDS))
        val clear = thread { racing.clear() }
        arrival.join(5_000)
        clear.join(5_000)
        assertTrue(racing.state.trail.isEmpty())
        assertEquals(racing.state, saved.last())
    }

    @Test fun aPlanFinishingAgainUnderItsKeyReplacesItsRecords() {
        journal.recordPlan(listOf(step("hold", "COMPLETED")), key = 7)
        journal.recordPlan(listOf(step("hold", "UNKNOWN")), key = 7) // its STOP check failed after the ACK
        assertEquals(listOf("UNKNOWN"), journal.state.history.map { it.outcome })
        assertEquals(listOf(1L), journal.state.history.map { it.planId })
        assertEquals(2L, journal.state.nextId)
        // Another plan in between: the old key no longer replaces anything.
        journal.recordPlan(listOf(step()), key = 8)
        journal.recordPlan(listOf(step("hold", "COMPLETED")), key = 7)
        assertEquals(listOf(1L, 2L, 3L), journal.state.history.map { it.planId })
        // No key: always a new plan.
        journal.recordPlan(listOf(step()))
        journal.recordPlan(listOf(step()))
        assertEquals(5, journal.state.history.size)
    }

    @Test fun clearForgetsEverythingButHome() {
        journal.observeHome(ned(0.0, 0.0, 0.0), origin())
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.markPoint(1, ned(1.0, 0.0))
        journal.recordPlan(listOf(step()))
        journal.clear()
        val state = journal.state
        assertEquals(ned(0.0, 0.0, 0.0), state.home)
        assertEquals(origin(), state.origin)
        assertTrue(state.trail.isEmpty() && state.points.isEmpty() && state.history.isEmpty())
        assertEquals(2L, state.nextId)
    }

    @Test fun aRestoredMemoryWithoutAHomeAndFrameKeepsOnlyItsHistory() {
        val saved = MemoryState(trail = listOf(Waypoint(ned(1.0, 1.0), "a", 0)),
            points = mapOf(1 to Waypoint(ned(2.0, 2.0), "Point 1", 0)),
            history = listOf(CommandRecord(4, 4, 0, "land", emptyMap(), "Land", "typed", "COMPLETED")), nextId = 5)
        val restored = FlightJournal(RecordingStore(saved)).state
        assertTrue(restored.trail.isEmpty() && restored.points.isEmpty())
        assertEquals(saved.history, restored.history)
        assertEquals(5L, restored.nextId)
        // A home without its frame (a memory saved before the frame check) cannot be checked either.
        val withHome = saved.copy(home = ned(0.0, 0.0, 0.0))
        assertTrue(FlightJournal(RecordingStore(withHome)).state.points.isEmpty())
        val withFrame = withHome.copy(origin = origin())
        assertEquals(withFrame, FlightJournal(RecordingStore(withFrame)).state)
    }

    @Test fun everyChangeIsSavedOnce() {
        journal.takeoffCompleted(ned(0.0, 0.0))
        journal.takeoffCompleted(ned(0.0, 0.0)) // same state: not saved again
        journal.markPoint(1, ned(0.0, 0.0))
        assertEquals(2, store.saved.size)
        assertEquals(journal.state, store.saved.last())
    }
}
