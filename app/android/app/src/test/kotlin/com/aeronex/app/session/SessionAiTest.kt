package com.aeronex.app.session

import com.aeronex.app.ai.AiAvailability
import com.aeronex.app.ai.LfmPlanner
import com.aeronex.app.ai.PlannerResult
import com.aeronex.app.ai.ToolCallResult
import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import com.aeronex.app.command.TranscriptInspector
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0.4.0 AI path in the session (spec A1-A6) with a scripted planner and tool caller: rules first, the AI only for
 * "not understood", its plan grounded, validated and confirmed once, and STOP, a press, new text or a disconnect
 * cancelling it.
 */
@Suppress("UNCHECKED_CAST")
class SessionAiTest {
    private val vehicle = FakeArduCopter()
    private val ai = FakeAi()
    private val runtime get() = ai.runtime
    private val controller = SessionController(transports = { TransportLease(vehicle.transport()) },
        timingFor = { FAST_TIMING }, ai = ai.config)
    private val api = SessionApi(controller)
    private val udp = mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0)

    @After fun tearDown() {
        runtime.hold?.countDown()
        controller.close()
        vehicle.close()
    }

    private fun ok(method: String, arguments: Any?): Map<String, Any?> {
        val reply = api.call(method, arguments)
        assertTrue("$method: $reply", reply is SessionApi.Reply.Ok)
        return (reply as SessionApi.Reply.Ok).value as Map<String, Any?>
    }

    private fun text(text: String, language: String = "en-IN") =
        ok("submitText", mapOf("text" to text, "language" to language))

    private fun aiStatus() = controller.snapshot()["ai"] as Map<String, Any?>
    private fun pending() = controller.snapshot()["pending"] as Map<String, Any?>?

    private fun connect() {
        ok("connect", udp)
        waitFor("link up with a ready vehicle") {
            val snapshot = controller.snapshot()
            val vehicle = snapshot["vehicle"] as Map<String, Any?>
            (snapshot["link"] as Map<String, Any?>)["state"] == "up" && vehicle["connected"] == true &&
                vehicle["homeKnown"] == true && vehicle["positionReady"] == true
        }
        waitFor("AI available") { aiStatus()["available"] == true }
    }

    /** Submits [text] the rules do not understand and waits until the AI has answered. */
    private fun thinkThrough(text: String, language: String = "en-IN"): Map<String, Any?> {
        val before = (aiStatus()["request"] as Number).toLong()
        assertEquals(mapOf("thinking" to true, "request" to before + 1), text(text, language))
        waitFor("AI answered") { aiStatus().let { it["request"] == before + 1 && it["state"] == "IDLE" } }
        return aiStatus()
    }

    private fun rulesMessage(text: String) = (PlanParser.parseText(text, "en-IN") as ParseResult.Rejected).message

    // --- A1 ---

    @Test fun stopHoldAndStatusNeverGoThroughTheAi() {
        connect()
        assertEquals(true, text("stop")["immediate"])
        assertEquals(true, text("hold position")["immediate"])
        assertEquals("NOT_SENT", text("status")["outcome"])
        assertTrue(runtime.planned.isEmpty())
        assertEquals(0L, aiStatus()["request"])
    }

    @Test fun anAiSingleHoldOrStatusIsRefusedNeverRun() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("hold") }
        assertEquals(SafetySupervisor.AI_NO_IMMEDIATE, thinkThrough("circle the tree")["error"])
        assertNull(pending())
        assertNull(controller.snapshot()["run"])
        assertNull(controller.snapshot()["stop"])
    }

    // --- A2 ---

    @Test fun safetyRejectionsOfTheRulesAreFinal() {
        connect()
        for (said in listOf("don't take off to 3 m", "move forward -2 m", "move forward minus 2 m", "move forward 20 m",
            "move forward 0 m", "move forward 3 feet", "take off", "take off to 3 m and land after 5 seconds",
            "move forward 3 meters quickly")) {
            assertEquals(said, mapOf("error" to rulesMessage(said)), text(said))
        }
        assertEquals(TranscriptInspector.NEGATION, rulesMessage("don't take off to 3 m"))
        assertTrue(runtime.planned.isEmpty())
        assertEquals(0L, aiStatus()["request"])
    }

    @Test fun onlyNotUnderstoodGoesToTheAiWithTheGloss() {
        connect()
        runtime.plans = { FakeAiRuntime.steps() }
        assertEquals(AiRouter.REFUSED, thinkThrough("circle the tree")["error"])
        // Telugu typed text: the planner reads its English gloss; the operator sees what was heard and the gloss.
        val telugu = thinkThrough("ముందుకు మూడు మీటర్లు", "te-IN")
        assertEquals(listOf("circle the tree", "forward 3 meters"), runtime.planned)
        assertEquals("ముందుకు మూడు మీటర్లు", telugu["heard"])
        assertEquals("forward 3 meters", telugu["gloss"])
    }

    @Test fun aStepWithTwoNumbersGoesToThePlannerButASelfCorrectionStaysFinal() {
        connect()
        runtime.plans = { FakeAiRuntime.steps() }
        val split = "after taking off to 6 meters move left 4 meters"
        assertTrue(rulesMessage(split).endsWith(AiRouter.PLAIN_NUMBER))
        thinkThrough(split)
        assertEquals(listOf(split), runtime.planned)
        for (said in listOf("move forward 3 meters actually 4 meters", "move forward 3 meters sorry 4 meters")) {
            assertTrue(said, rulesMessage(said).endsWith(AiRouter.PLAIN_NUMBER))
            assertEquals(said, mapOf("error" to rulesMessage(said)), text(said))
        }
        assertEquals(listOf(split), runtime.planned)
    }

    // --- A3 ---

    @Test fun anAiPlanIsShownWithTheAiSourceAndNeedsOneConfirm() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters", "move forward 2 meters") }
        val status = thinkThrough("take off to 3 m then step forward 2 meters")
        assertNull(status["error"])
        assertEquals("take off to 3 meters, then move forward 2 meters", status["understoodAs"])
        val pending = pending()!!
        assertEquals("ai", pending["source"])
        assertEquals("1. Take off to 3 m; 2. Move forward 2 m", pending["summary"])
        assertTrue((pending["steps"] as List<Map<String, Any?>>).all { it["source"] == "ai" })
        assertTrue(runtime.called.isEmpty()) // the rules read both planner steps
        assertEquals(mapOf("accepted" to true), ok("confirm", mapOf("token" to pending["token"])))
    }

    @Test fun aStepTheRulesCannotReadGoesToNeedle() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters", "glide forward 2 meters") }
        runtime.calls = { FakeAiRuntime.call(FlightCommand.MOVE, mapOf("direction" to "forward", "distance_m" to 2)) }
        assertNull(thinkThrough("take off to 3 m then step forward 2 meters")["error"])
        assertEquals(listOf("glide forward 2 meters"), runtime.called)
        assertEquals("1. Take off to 3 m; 2. Move forward 2 m", pending()!!["summary"])
    }

    @Test fun aNeedleCallOfAnotherKindThanItsStepIsRefused() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("glide forward 3 meters") }
        runtime.calls = { FakeAiRuntime.call(FlightCommand.TAKEOFF, mapOf("altitude_m" to 3)) }
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID, thinkThrough("forward 3 meters")["error"])
        assertNull(pending())
    }

    @Test fun anAiPlanStillPassesThePlanValidator() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("move forward 3 meters") }
        assertEquals("The drone is on the ground. Start with a takeoff height, for example: take off to 3 m and fly " +
            "forward 3 m.", thinkThrough("forward 3 meters")["error"])
        assertNull(pending())
    }

    @Test fun theChannelNeverTakesTheAiSource() {
        connect()
        val reply = api.call("submitText", mapOf("text" to "land", "language" to "en", "source" to "ai"))
        assertEquals(SessionException.BAD_ARGS, (reply as SessionApi.Reply.Failed).code)
        val plan = mapOf("steps" to listOf(mapOf("name" to "takeoff", "arguments" to mapOf("altitude_m" to 3), "source" to "rules")),
            "source" to "ai")
        assertEquals(SessionException.BAD_ARGS, (api.call("submitPlan", plan) as SessionApi.Reply.Failed).code)
        val aiStep = mapOf("steps" to listOf(mapOf("name" to "takeoff", "arguments" to mapOf("altitude_m" to 3), "source" to "ai")),
            "source" to "typed")
        assertEquals(mapOf("error" to SessionSchema.aiStep(1)), ok("submitPlan", aiStep))
        assertNull(pending())
    }

    // --- A4 ---

    @Test fun theAiCannotInventNumbersOrDirections() {
        connect()
        for (steps in listOf(listOf("move forward 1 meters"), listOf("move left 3 meters"), listOf("take off to 5 meters"))) {
            runtime.plans = { FakeAiRuntime.steps(*steps.toTypedArray()) }
            assertEquals("$steps", LfmPlanner.NOT_WHAT_YOU_SAID, thinkThrough("forward 3 meters")["error"])
        }
        // "a couple" is no number: the planner's 2 is invented.
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters", "move right 2 meters", "land") }
        assertEquals(LfmPlanner.NOT_WHAT_YOU_SAID,
            thinkThrough("take off to 3 m then slide over a couple of meters then land")["error"])
        assertNull(pending())
    }

    // --- A5 ---

    private fun startThinking(text: String = "forward 3 meters"): Long {
        runtime.hold = CountDownLatch(1)
        runtime.started = CountDownLatch(1)
        val answer = text(text)
        assertEquals(true, answer["thinking"])
        assertTrue("planner started", runtime.started.await(5, TimeUnit.SECONDS))
        assertEquals("THINKING", aiStatus()["state"])
        return (answer["request"] as Number).toLong()
    }

    private fun assertCancelled() {
        waitFor("AI idle") { aiStatus()["state"] == "IDLE" }
        assertTrue(runtime.cancels.get() > 0)
        assertNull(aiStatus()["error"])
    }

    @Test fun stopCancelsTheThinkingAndALateAnswerIsDiscarded() {
        connect()
        runtime.honourCancel = false // a planner that answers anyway: the session must drop it
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters") }
        startThinking("take off to 3 m then circle the tree")
        controller.stop(StopTrigger.BUTTON)
        assertCancelled()
        runtime.hold!!.countDown()
        Thread.sleep(300)
        assertNull(pending())
        assertNull(aiStatus()["understoodAs"])
    }

    @Test fun aNewPressNewTextADisconnectOrCancelEndTheThinking() {
        connect()
        startThinking()
        controller.pttDown()
        assertCancelled()

        startThinking()
        runtime.hold = null
        runtime.plans = { FakeAiRuntime.steps() }
        val second = text("circle the tree")
        waitFor("second answered") { aiStatus().let { it["request"] == second["request"] && it["state"] == "IDLE" } }
        assertEquals(AiRouter.REFUSED, aiStatus()["error"])

        startThinking()
        assertEquals(SessionApi.Reply.Ok(null), api.call("cancelAi", null))
        assertCancelled()

        startThinking()
        controller.disconnect()
        assertCancelled()
    }

    @Test fun thePlannerAndNeedleGetTheirBudgets() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("glide forward 3 meters") }
        runtime.calls = { FakeAiRuntime.call(FlightCommand.MOVE, mapOf("direction" to "forward", "distance_m" to 3)) }
        thinkThrough("forward 3 meters")
        assertTrue(runtime.planTimeouts.single() in 1..4_500)
        assertTrue(runtime.callBudgets.single() in 1..500)
        runtime.plans = { PlannerResult.TimedOut(4_500) }
        assertEquals(AiRouter.TOO_SLOW, thinkThrough("forward 3 meters")["error"])
        runtime.plans = { FakeAiRuntime.steps("glide forward 3 meters") }
        runtime.calls = { ToolCallResult.TimedOut(600) }
        assertEquals(AiRouter.TOO_SLOW, thinkThrough("forward 3 meters")["error"])
    }

    @Test fun theLatencyKeysAreReported() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("glide forward 3 meters") }
        runtime.calls = { FakeAiRuntime.call(FlightCommand.MOVE, mapOf("direction" to "forward", "distance_m" to 3)) }
        thinkThrough("forward 3 meters")
        val latency = controller.snapshot()["latency"] as Map<String, Any?>
        for (key in listOf("aiGloss", "aiPlan", "aiTools", "aiTotal")) {
            assertEquals(key, 1, ((latency[key] as Map<String, Any?>?) ?: error("no $key"))["n"])
        }
    }

    // --- A6 ---

    @Test fun unavailableAiSaysWhyInPlainWords() {
        val offline = FakeAi().also { it.availability = AiAvailability(false, AiAvailability.NEEDS_ARM64) }
        val x86 = SessionController(ai = offline.config)
        try {
            waitFor("availability checked") { (x86.snapshot()["ai"] as Map<String, Any?>)["reason"] == AiAvailability.NEEDS_ARM64 }
            assertEquals(false, (x86.snapshot()["ai"] as Map<String, Any?>)["available"])
            assertEquals(mapOf("error" to rulesMessage("circle the tree") + " " + AiRouter.unavailable(AiAvailability.NEEDS_ARM64)),
                x86.submitText("circle the tree", "en-IN"))
            assertEquals(0, offline.runtimesBuilt.get())
            assertTrue(offline.runtime.planned.isEmpty())
        } finally {
            x86.close()
        }
    }

    @Test fun thePlannerUnloadsWhenIdleAndOnTrimMemory() {
        connect()
        waitFor("idle checks") { runtime.idleChecks.get() >= 2 }
        controller.trimMemory(80)
        assertTrue(runtime.unloads.get() >= 1)
    }

    @Test fun thePlannerWarmsUpBeforeItsFirstPlanAndAgainAfterAnUnload() {
        connect()
        waitFor("warmed up once AI is available") { runtime.warmUps.get() == 1 }
        thinkThrough("circle the tree")
        assertEquals(listOf("warmUp", "plan"), runtime.events)
        // onTrimMemory: the next request loads the planner again before it plans.
        controller.trimMemory(80)
        thinkThrough("circle the tree")
        assertEquals(listOf("warmUp", "plan", "unload", "warmUp", "plan"), runtime.events)
        // 120 s idle: a push-to-talk press loads it while the operator speaks.
        runtime.idle = true
        waitFor("idle unload") { "idleUnload" in runtime.events }
        runtime.idle = false
        controller.pttDown()
        waitFor("warmed up on the press") { runtime.warmUps.get() == 3 }
        thinkThrough("circle the tree")
        assertEquals(listOf("warmUp", "plan", "unload", "warmUp", "plan", "idleUnload", "warmUp", "plan"), runtime.events)
    }

    // --- S2 and the Cancel race ---

    @Test fun goingToTheBackgroundCancelsTheThinkingAndALateAnswerIsDiscarded() {
        connect()
        runtime.honourCancel = false
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters") }
        startThinking("take off to 3 m then circle the tree")
        controller.invalidatePending(SessionController.BACKGROUNDED)
        assertCancelled()
        runtime.hold!!.countDown()
        Thread.sleep(300)
        assertNull(pending())
        assertNull(aiStatus()["understoodAs"])
    }

    @Test fun aCancelThatLosesTheRaceWithDeliveryStillDropsTheAiPlan() {
        connect()
        runtime.plans = { FakeAiRuntime.steps("take off to 3 meters") }
        val cancelled = AtomicBoolean()
        controller.addChangeListener {
            // On the supervisor thread: the AI plan is pending while the AI thread still finishes the request.
            if (controller.supervisorState().pending != null && aiStatus()["state"] == "THINKING" &&
                cancelled.compareAndSet(false, true)) controller.cancelAi()
        }
        assertEquals(true, text("take off to 3 m then circle the tree")["thinking"])
        waitFor("cancelled after delivery") { cancelled.get() && aiStatus()["state"] == "IDLE" }
        Thread.sleep(300)
        assertNull(pending())
        assertNull(aiStatus()["understoodAs"])
        assertNull(aiStatus()["error"])
    }

    @Test fun theVehicleIsOnTheGroundOnlyWhenConnectedDisarmedAndLanded() {
        assertEquals(false, controller.onTheGround())
        connect()
        waitFor("on the ground") { controller.onTheGround() }
        controller.disconnect()
        assertEquals(false, controller.onTheGround())
    }
}
