package com.aeronex.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopAlarmSoundTest {
    private class FakeTone : AlarmTone {
        var played = 0
        var released = 0
        override fun play() {
            played++
        }
        override fun release() {
            released++
        }
    }

    /** The main thread's delayed posts, run by hand. */
    private class FakeScheduler {
        val posted = ArrayList<Pair<Runnable, Long>>()
        fun schedule(task: Runnable, delayMs: Long) {
            posted += task to delayMs
        }
        fun unschedule(task: Runnable) {
            posted.removeAll { it.first === task }
        }
        /** Runs what is due after one period, like the looper would. */
        fun runNext() {
            val (task, _) = posted.removeAt(0)
            task.run()
        }
    }

    private val tone = FakeTone()
    private val scheduler = FakeScheduler()
    private val alarm = StopAlarmSound(tone, scheduler::schedule, scheduler::unschedule, periodMs = 1_000)

    @Test fun soundsAtOnceAndRepeatsEveryPeriodWhileRaised() {
        alarm.update(false)
        assertEquals(0, tone.played)
        alarm.update(true)
        assertTrue(alarm.sounding)
        assertEquals("at once, not after a period", 1, tone.played)
        assertEquals(listOf(1_000L), scheduler.posted.map { it.second })
        repeat(4) { scheduler.runNext() }
        assertEquals(5, tone.played)
        assertEquals("one repeat queued at a time", 1, scheduler.posted.size)
    }

    @Test fun repeatedUpdatesWhileRaisedDoNotStackRepeats() {
        alarm.update(true)
        repeat(10) { alarm.update(true) }
        assertEquals(1, tone.played)
        assertEquals(1, scheduler.posted.size)
    }

    @Test fun stopsWhenAcknowledgedOrClearedAndSoundsAgainForTheNextAlarm() {
        alarm.update(true)
        scheduler.runNext()
        alarm.update(false) // Got it, or the supervisor cleared it
        assertFalse(alarm.sounding)
        assertTrue("the repeat is cancelled", scheduler.posted.isEmpty())
        assertEquals(2, tone.played)
        alarm.update(true)
        assertEquals("a new alarm sounds again", 3, tone.played)
        assertEquals(1, scheduler.posted.size)
    }

    @Test fun aRepeatThatRunsAfterTheAlarmEndedIsSilent() {
        alarm.update(true)
        val stale = scheduler.posted.single().first
        alarm.update(false)
        stale.run() // already handed to the looper when the alarm ended
        assertEquals(1, tone.played)
        assertTrue(scheduler.posted.isEmpty())
    }

    @Test fun releaseSilencesAndFreesTheTone() {
        alarm.update(true)
        alarm.release()
        assertFalse(alarm.sounding)
        assertTrue(scheduler.posted.isEmpty())
        assertEquals(1, tone.released)
    }
}
