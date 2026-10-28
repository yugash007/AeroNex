package com.aeronex.app.session

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.safety.ConfirmResult
import com.aeronex.app.safety.PendingPlan
import com.aeronex.app.safety.PlanSource
import com.aeronex.app.safety.StepOutcome
import com.aeronex.app.safety.SubmitResult
import com.aeronex.app.safety.SupervisorState
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class SessionSchemaTest {
    private fun step(name: String, arguments: Map<String, Any?> = emptyMap(), source: String = "rules") =
        mapOf("name" to name, "arguments" to arguments, "source" to source)
    private fun request(vararg steps: Map<String, Any?>, source: String = "typed", utterance: String? = null) =
        mapOf("steps" to steps.toList(), "source" to source, "utterance" to utterance)
    private val takeoff3 = step("takeoff", mapOf("altitude_m" to 3))
    private val forward2 = step("move_relative", mapOf("direction" to "forward", "distance_m" to 2.5))

    private fun contentError(arguments: Any?): String = try {
        SessionSchema.parsePlan(arguments)
        fail("accepted $arguments")
        throw AssertionError()
    } catch (e: CommandFormatException) {
        e.message!!
    }

    private fun shapeError(arguments: Any?): SessionException = try {
        SessionSchema.parsePlan(arguments)
        fail("accepted $arguments")
        throw AssertionError()
    } catch (e: SessionException) {
        e
    }

    @Test fun aPlanFromDartBecomesTheKotlinPlan() {
        val parsed = SessionSchema.parsePlan(request(takeoff3, forward2 + ("clause" to "forward 2.5"), step("land"),
            source = "phone", utterance = "take off to 3 m, forward 2.5, land"))
        assertEquals(PlanSource.PHONE, parsed.source)
        assertEquals("take off to 3 m, forward 2.5, land", parsed.utterance)
        assertEquals("1. Take off to 3 m; 2. Move forward 2.5 m; 3. Land", parsed.plan.toString())
        assertEquals(listOf(StepSource.RULES, StepSource.RULES, StepSource.RULES), parsed.plan.steps.map { it.source })
        assertEquals("forward 2.5", parsed.plan.steps[1].clause)
        assertEquals(PlanSource.TAP, SessionSchema.parsePlan(request(step("hold", source = "fixture"), source = "tap")).source)
        assertEquals(StepSource.FIXTURE, SessionSchema.parsePlan(request(step("hold", source = "fixture"))).plan.steps[0].source)
    }

    @Test fun planContentErrorsAreTheV020MessagesVerbatim() {
        assertEquals("Tool is not allowed.", contentError(request(step("arm"))))
        assertEquals("Specify a takeoff altitude of 1–10 m.", contentError(request(step("takeoff", mapOf("altitude_m" to 11)))))
        assertEquals("Specify a direction and distance >0–10 m.",
            contentError(request(step("move_relative", mapOf("direction" to "north", "distance_m" to 2)))))
        assertEquals("This tool accepts no arguments.", contentError(request(step("land", mapOf("now" to true)))))
        assertEquals("Say a command first.", contentError(request()))
        assertEquals("Say at most 5 steps at a time.", contentError(request(*Array(6) { forward2 })))
        assertEquals("Land can only be the last step.", contentError(request(step("land"), forward2)))
        assertEquals("Takeoff can only be the first step.", contentError(request(forward2, takeoff3)))
        assertEquals("Ask for status on its own.", contentError(request(step("get_status"), forward2)))
    }

    @Test fun aStepTheRulesDidNotMatchIsRefused() {
        // v2 has no AI fallback (spec 4.9, S1): the Dart client's AI-proposed steps are not executed.
        assertEquals(SessionSchema.aiStep(2), contentError(request(takeoff3, forward2 + ("source" to "ai"))))
        assertEquals("Step 2 was not matched by the command rules. Say it again with the supported words.", SessionSchema.aiStep(2))
    }

    @Test fun aMalformedRequestIsAProgrammingError() {
        assertEquals(SessionException.BAD_ARGS, shapeError(null).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(mapOf("steps" to "takeoff", "source" to "typed")).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(request(takeoff3, source = "voice")).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(mapOf("steps" to listOf(takeoff3))).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(request(takeoff3 - "source")).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(request(takeoff3 + ("source" to "llm"))).code)
        assertEquals(SessionException.BAD_ARGS, shapeError(mapOf("steps" to listOf("takeoff"), "source" to "typed")).code)
    }

    @Test fun submitAndConfirmResultsAreMaps() {
        val plan = FlightPlan(listOf(PlanStep(FlightCommand.of(FlightCommand.LAND), StepSource.RULES)))
        val pending = PendingPlan("t-1", plan, PlanSource.TYPED, expiresAtMs = 20_000)
        assertEquals(mapOf("token" to "t-1", "expiresInMs" to 15_000L, "summary" to "1. Land"),
            SessionSchema.submitted(SubmitResult.AwaitingConfirmation(pending), nowMs = 5_000))
        assertEquals(0L, SessionSchema.submitted(SubmitResult.AwaitingConfirmation(pending), nowMs = 25_000)["expiresInMs"])
        assertEquals(mapOf("immediate" to true, "outcome" to "NOT_SENT", "message" to "NOT SENT — dry run: land({})"),
            SessionSchema.submitted(SubmitResult.Immediate(StepOutcome.dryRun(plan.steps[0])), nowMs = 0))
        assertEquals(mapOf("error" to "A plan is running. Say stop first."),
            SessionSchema.submitted(SubmitResult.Rejected("A plan is running. Say stop first."), nowMs = 0))
        assertEquals(mapOf("accepted" to true), SessionSchema.confirmed(ConfirmResult.Started))
        assertEquals(mapOf("accepted" to false, "error" to "No plan is waiting for confirmation."),
            SessionSchema.confirmed(ConfirmResult.Rejected("No plan is waiting for confirmation.")))
    }

    private fun snapshot(vehicle: VehicleState) =
        SessionSchema.snapshot(1, 1_000, LinkInfo.down(false), SupervisorState(), vehicle, emptyMap(),
            VoiceStatus.off("en", VoiceReason.NOT_STARTED))

    @Test fun theVehicleSectionReportsNoOwnerWhileNotConnectedAndRoundsDerivedDistances() {
        val disconnected = snapshot(VehicleState(atMs = 1_000, mode = 4, modeOwner = ModeOwner.EXTERNAL))["vehicle"] as Map<String, Any?>
        assertEquals(false, disconnected["connected"])
        assertNull(disconnected["modeOwner"])
        assertEquals("GUIDED", disconnected["modeName"])
        val flying = snapshot(VehicleState(atMs = 1_000, mode = 4, modeOwner = ModeOwner.APP, position = Ned(3.0, 4.0001, -3.0),
            velocity = Ned(1.0, 1.0, 1.0), home = Ned(0.0, 0.0, 0.0), ageMs = mapOf(0 to 10L)))["vehicle"] as Map<String, Any?>
        assertEquals("APP", flying["modeOwner"])
        assertEquals(5.0, flying["homeDistanceM"])
        assertEquals(1.73, flying["speed"])
        assertEquals(10L, (flying["ages"] as Map<String, Any?>)["heartbeat"])
        assertNull((flying["ages"] as Map<String, Any?>)["home"])
    }
}
