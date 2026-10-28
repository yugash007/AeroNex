package com.aeronex.app.ai

import com.aeronex.app.command.model.FlightCommand
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NeedleToolCallerTest {
    private val engine = FakeAiEngine()
    private var now = 5_000L
    private val model = File("/data/models/needle3.cact")
    private val caller = NeedleToolCaller(engine, model, clock = { now })

    private fun call(step: String, reply: String, budgetMs: Long = AiBudget.NEEDLE_STEP_MS): ToolCallResult {
        engine.toolReply = { reply }
        return caller.call(step, budgetMs = budgetMs)
    }

    @Test fun aTakeoffCallBecomesAValidatedFlightCommand() {
        val result = call("take off to 3 meters", FakeAiEngine.callJson("takeoff", """{"altitude_m":3.0}"""))
        val command = (result as ToolCallResult.Call).command
        assertEquals(FlightCommand.TAKEOFF, command.name)
        assertEquals(3.0, command.altitudeM!!, 0.0)
        assertEquals(listOf("toolCall ${model.path} | take off to 3 meters"), engine.calls)
    }

    @Test fun aMoveKeepsItsDirectionAndDecimalDistance() {
        val command = (call("move up 2.5 meters",
            FakeAiEngine.callJson("move_relative", """{"direction":"up","distance_m":2.5}""")) as ToolCallResult.Call).command
        assertEquals(mapOf("direction" to "up", "distance_m" to 2.5), command.arguments)
        assertEquals("Move up 2.5 m", command.description)
    }

    @Test fun argumentFreeToolsMapToo() {
        for (name in listOf("land", "hold", "get_status")) {
            assertEquals(name, ((call("x", FakeAiEngine.callJson(name))) as ToolCallResult.Call).command.name)
        }
    }

    @Test fun noCallKeepsNeedlesOrTheMappersReason() {
        val result = call("do not land", """{"call":null,"reason":"negated","ms":70.0}""")
        assertEquals("negated", (result as ToolCallResult.NoCall).reason)
    }

    @Test fun aCallTheFlightCommandRulesRejectIsNoCall() {
        val cases = mapOf(
            "unknown tool" to FakeAiEngine.callJson("disarm"),
            "out of range" to FakeAiEngine.callJson("takeoff", """{"altitude_m":12}"""),
            "zero distance" to FakeAiEngine.callJson("move_relative", """{"direction":"up","distance_m":0}"""),
            "bad direction" to FakeAiEngine.callJson("move_relative", """{"direction":"north","distance_m":2}"""),
            "extra argument" to FakeAiEngine.callJson("land", """{"speed":2}"""),
            "missing argument" to FakeAiEngine.callJson("takeoff"),
            "string number" to FakeAiEngine.callJson("takeoff", """{"altitude_m":"3"}"""),
            "nested argument" to FakeAiEngine.callJson("takeoff", """{"altitude_m":{"value":3}}"""),
        )
        for ((case, reply) in cases) {
            val result = call("take off to 3 meters", reply)
            assertTrue(case, result is ToolCallResult.NoCall)
            assertEquals(case, ToolCallResult.INVALID_CALL, (result as ToolCallResult.NoCall).reason)
        }
    }

    @Test fun unreadableOutputOrANativeErrorFails() {
        assertTrue(call("land", "nonsense") is ToolCallResult.Failed)
        assertTrue(call("land", """{"reason":"no_call"}""") is ToolCallResult.Failed)
        engine.toolReply = { throw IllegalStateException("Cannot load the Cactus runtime: dlopen failed") }
        assertEquals("Cannot load the Cactus runtime: dlopen failed",
            (caller.call("land") as ToolCallResult.Failed).message)
    }

    @Test fun cancellationIsCheckedBeforeAndAfterTheNativeCall() {
        assertTrue(caller.call("land", cancelled = { true }) is ToolCallResult.Cancelled)
        assertTrue(engine.calls.isEmpty())
        var cancelled = false
        engine.toolReply = { cancelled = true; FakeAiEngine.callJson("land") }
        assertTrue("a late call is discarded", caller.call("land", cancelled = { cancelled }) is ToolCallResult.Cancelled)
        engine.toolReply = { cancelled = true; throw IllegalStateException("Operation cancelled.") }
        assertTrue(caller.call("land", cancelled = { cancelled }) is ToolCallResult.Cancelled)
    }

    @Test fun aCallOverItsStepBudgetIsDiscarded() {
        engine.toolReply = { now += 501; FakeAiEngine.callJson("land") }
        val late = caller.call("land", budgetMs = 500)
        assertEquals(501L, (late as ToolCallResult.TimedOut).elapsedMs)
        engine.toolReply = { now += 500; FakeAiEngine.callJson("land") }
        assertTrue(caller.call("land", budgetMs = 500) is ToolCallResult.Call)
    }

    @Test fun aBlankOrOverlongStepNeverReachesNeedle() {
        assertTrue(caller.call(" ") is ToolCallResult.Failed)
        assertTrue(caller.call("x".repeat(NeedleToolCaller.MAX_STEP_CHARS + 1)) is ToolCallResult.Failed)
        assertTrue(engine.calls.isEmpty())
    }

    @Test fun theCallerNeverResetsAPendingCancel() {
        caller.call("land")
        assertTrue(engine.calls.none { it == "reset" })
        caller.cancel()
        assertTrue(engine.nativeCancelled)
    }
}
