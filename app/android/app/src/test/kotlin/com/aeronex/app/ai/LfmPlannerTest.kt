package com.aeronex.app.ai

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** org.json parses the native answers, so these run on Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LfmPlannerTest {
    private val engine = FakeAiEngine()
    private var now = 1_000L
    private val model = File("/data/models/LFM2.5-VL-450M-Q4_K_M.gguf")
    private val planner = LfmPlanner(engine, model, threads = 4, clock = { now })

    @Test fun stepsComeBackInOrderFromOneNativeCall() {
        engine.planReply = { FakeAiEngine.planJson(listOf("move up 3 meters", "move forward 2 meters")) }
        val result = planner.plan("go up three meters and then forward two")
        result as PlannerResult.Steps
        assertEquals(listOf("move up 3 meters", "move forward 2 meters"), result.steps)
        assertEquals(PlannerStats(loadMs = 0.0, promptMs = 35.5, generateMs = 210.25, promptTokens = 420,
            generatedTokens = 17), result.stats)
        assertEquals("""{"steps":["move up 3 meters","move forward 2 meters"]}""", result.raw)
        assertEquals(listOf("reset", "plan ${model.path} | go up three meters and then forward two | 4 | " +
            "${AiBudget.PLANNER_MS}"), engine.calls)
    }

    @Test fun anEmptyListIsTheModelsRefusal() {
        assertTrue(planner.plan("please don't land") is PlannerResult.Refused)
    }

    @Test fun fiveStepsAreTheLimitAndASixthFailsTheWholePlan() {
        val five = List(5) { "move up 1 meters" }
        engine.planReply = { FakeAiEngine.planJson(five) }
        assertEquals(five, (planner.plan("up five times") as PlannerResult.Steps).steps)
        engine.planReply = { FakeAiEngine.planJson(five + "land") }
        val failed = planner.plan("up five times and land")
        assertTrue("never truncated to five", failed is PlannerResult.Failed)
    }

    @Test fun aStepOutsideThePlannerGrammarFails() {
        for (bad in listOf("Move up 3 meters", "move up -3 meters", "", "move  up", "one two three four five six seven " +
            "eight nine", "take off to 1e3 meters", "land; arm")) {
            engine.planReply = { FakeAiEngine.planJson(listOf("hold", bad)) }
            assertTrue(bad, planner.plan("hold then something") is PlannerResult.Failed)
        }
    }

    @Test fun anIncompleteOrMalformedAnswerFails() {
        engine.planReply = { FakeAiEngine.planJson(listOf("move up 3 meters"), complete = false, stop = "limit") }
        assertTrue(planner.plan("up three") is PlannerResult.Failed)
        engine.planReply = { """{"complete":true,"stop":"done"}""" }
        assertTrue(planner.plan("up three") is PlannerResult.Failed)
        engine.planReply = { "not json" }
        assertTrue(planner.plan("up three") is PlannerResult.Failed)
    }

    @Test fun theNativeDeadlineAndCancelAreReported() {
        engine.planReply = { FakeAiEngine.planJson(emptyList(), complete = false, stop = "timeout") }
        assertTrue(planner.plan("up three") is PlannerResult.TimedOut)
        engine.planReply = { FakeAiEngine.planJson(emptyList(), complete = false, stop = "cancelled") }
        assertTrue(planner.plan("up three") is PlannerResult.Cancelled)
    }

    @Test fun aRequestCancelledBeforeItStartsNeverReachesTheModel() {
        val result = planner.plan("up three", cancelled = { true })
        assertTrue(result is PlannerResult.Cancelled)
        assertTrue(engine.calls.none { it.startsWith("plan") })
    }

    @Test fun cancelWhileRunningAbortsTheNativeCallAndWins() {
        engine.planReply = {
            planner.cancel() // STOP, a new PTT press or disconnect, from another thread
            FakeAiEngine.planJson(listOf("move up 3 meters"))
        }
        val result = planner.plan("up three")
        assertTrue("a late result is discarded", result is PlannerResult.Cancelled)
        assertTrue(engine.nativeCancelled)
        // The next request starts clean: reset() clears the previous cancel before its own native call.
        engine.planReply = { FakeAiEngine.planJson(listOf("land")) }
        assertEquals(listOf("land"), (planner.plan("land now") as PlannerResult.Steps).steps)
        assertEquals("reset", engine.calls[engine.calls.size - 2])
    }

    @Test fun aNativeErrorIsAFailureWithItsMessageUnlessCancelled() {
        engine.planReply = { throw IllegalStateException("Cannot load the LFM planner model. Import it again.") }
        assertEquals("Cannot load the LFM planner model. Import it again.",
            (planner.plan("up three") as PlannerResult.Failed).message)
        var cancelled = false
        engine.planReply = { cancelled = true; throw IllegalStateException("aborted") }
        assertTrue(planner.plan("up three", cancelled = { cancelled }) is PlannerResult.Cancelled)
    }

    @Test fun blankOrOverlongTextIsRefusedWithoutTheModel() {
        assertTrue(planner.plan("   ") is PlannerResult.Failed)
        assertTrue(planner.plan("a".repeat(LfmPlanner.MAX_INPUT_CHARS + 1)) is PlannerResult.Failed)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun elapsedTimeIsMeasuredAroundTheWholeCall() {
        engine.planReply = { now += 640; FakeAiEngine.planJson(listOf("land")) }
        assertEquals(640L, planner.plan("land").elapsedMs)
    }

    @Test fun unloadDuringAPlanIsDeferredUntilItEnds() {
        engine.planReply = {
            planner.unload() // onTrimMemory on the main thread must never wait for the model
            assertFalse(engine.calls.contains("unload"))
            FakeAiEngine.planJson(listOf("land"))
        }
        planner.plan("land")
        assertEquals("unload", engine.calls.last())
        assertFalse(planner.loaded)
    }

    @Test fun theModelUnloadsAfterTwoMinutesIdle() {
        engine.planReply = { FakeAiEngine.planJson(listOf("land")) }
        assertFalse("nothing to unload before first use", planner.unloadIfIdle())
        planner.plan("land")
        assertTrue(planner.loaded)
        now += AiBudget.IDLE_UNLOAD_MS - 1
        assertFalse(planner.unloadIfIdle())
        now += 1
        assertTrue(planner.unloadIfIdle())
        assertEquals("unload", engine.calls.last())
        assertFalse(planner.unloadIfIdle())
    }

    @Test fun aStepWithACommandTheOperatorNeverSaidIsUngrounded() {
        val cases = mapOf(
            "turn off the motors" to listOf("hold"),
            "what's the drone status" to listOf("land"),
            "first climb three meters" to listOf("take off to 3 meters"),
            "take off to two and a half meters" to listOf("take off to 2.5 meters", "hold"),
        )
        for ((said, steps) in cases) {
            engine.planReply = { FakeAiEngine.planJson(steps) }
            val result = planner.plan(said)
            assertTrue(said, result is PlannerResult.Ungrounded)
            assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, (result as PlannerResult.Ungrounded).message)
        }
    }

    @Test fun aCommandTheOperatorSaidMustBeInThePlan() {
        engine.planReply = { FakeAiEngine.planJson(listOf("move back 2 meters")) }
        assertTrue(planner.plan("before you land, go back two meters") is PlannerResult.Ungrounded)
        engine.planReply = { FakeAiEngine.planJson(listOf("move left 4 meters")) }
        assertTrue(planner.plan("after taking off to six meters move left four meters") is PlannerResult.Ungrounded)
    }

    @Test fun groundedCommandsPassInEnglishAndInAGloss() {
        val cases = mapOf(
            "hover in place" to listOf("hold"),
            "is the battery okay" to listOf("status"),
            "Take off to five meters then touch down" to listOf("take off to 5 meters", "land"),
            "first 4 meter to takeoff do, after that 3 meter right go and then hover do" to
                listOf("take off to 4 meters", "move right 3 meters", "hover"),
            "climb two meters" to listOf("move up 2 meters"),
            // "wait" and "hold on" are not requests to hold, so they do not demand a hold step.
            "wait, go left two meters" to listOf("move left 2 meters"),
            "hold on, go right one meter" to listOf("move right 1 meter"),
        )
        for ((said, steps) in cases) {
            engine.planReply = { FakeAiEngine.planJson(steps) }
            assertEquals(said, steps, (planner.plan(said) as PlannerResult.Steps).steps)
        }
    }

    @Test fun warmUpLoadsTheModelAhead() {
        assertEquals(12.5, planner.warmUp(), 0.0)
        assertEquals("warmUp ${model.path} | 4", engine.calls.single())
        assertTrue(planner.loaded)
    }
}
