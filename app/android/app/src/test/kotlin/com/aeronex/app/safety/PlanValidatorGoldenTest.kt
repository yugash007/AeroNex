package com.aeronex.app.safety

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * The T5 gate (app/test/golden/README.md): every golden vector that has a `plan` must give exactly its v0.2.0
 * outcome through FlightCommand, FlightPlan and PlanValidator.
 *
 * The vectors are read in place from app/test/golden/plan_vectors.json (the frozen contract), not copied, so
 * there is one source of truth. Gradle runs unit tests in app/android/app; the lookup walks up from there.
 */
@Suppress("UNCHECKED_CAST")
class PlanValidatorGoldenTest {
    private val validator = PlanValidator()

    private fun vectors(): List<Map<String, Any?>> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf("test/golden/plan_vectors.json", "app/test/golden/plan_vectors.json")) {
                val file = File(dir, candidate)
                if (file.isFile) return MiniJson.parse(file.readText(Charsets.UTF_8)) as List<Map<String, Any?>>
            }
            dir = dir.parentFile
        }
        error("app/test/golden/plan_vectors.json not found above ${System.getProperty("user.dir")}")
    }

    /**
     * The recorded {landed, altitude} as a connected, fresh vehicle. Home is neutral: known, at the vehicle's
     * horizontal start point, heading north. A null altitude was not reported, so GLOBAL_POSITION_INT is absent.
     */
    private fun vehicle(recorded: Map<String, Any?>?): VehicleState? = recorded?.let {
        val landed = it["landed"] as Boolean
        val altitude = (it["altitude"] as Number?)?.toDouble()
        val fresh = listOfNotNull(0, 30, 32, 242, 245, altitude?.let { 33 })
        VehicleState(
            landedState = if (landed) VehicleState.LANDED_ON_GROUND else VehicleState.LANDED_IN_AIR,
            altitudeRelM = altitude, position = Ned(0.0, 0.0, -(altitude ?: 0.0)), yaw = 0.0,
            home = Ned(0.0, 0.0, 0.0), ageMs = fresh.associateWith { 0L },
        )
    }

    /** Validates a recorded plan; returns the accepted steps, or the rejection message. */
    private fun outcome(plan: List<Map<String, Any?>>, vehicle: VehicleState?): Any = try {
        val steps = plan.map { PlanStep(FlightCommand.fromToolCalls(listOf(it)), StepSource.RULES) }
        validator.validate(steps, vehicle) ?: FlightPlan(steps).steps
    } catch (e: CommandFormatException) {
        e.message!!
    }

    private fun sameArguments(actual: Map<String, Any>, expected: Map<String, Any?>) =
        actual.keys == expected.keys && actual.all { (key, value) ->
            val other = expected[key]
            if (value is Number && other is Number) value.toDouble() == other.toDouble() else value == other
        }

    private fun matches(outcome: Any, expect: Map<String, Any?>): Boolean {
        val error = expect["error"]
        if (error != null) return outcome == error
        val steps = outcome as? List<*> ?: return false
        val expected = expect["steps"] as List<Map<String, Any?>>
        return steps.size == expected.size && steps.zip(expected).all { (step, want) ->
            step as PlanStep
            step.command.name == want["name"] && step.source.wireName == want["source"] &&
                sameArguments(step.command.arguments, want["arguments"] as Map<String, Any?>)
        }
    }

    @Test fun everyRecordedPlanGivesItsRecordedOutcome() {
        val planVectors = vectors().filter { "plan" in it }
        // 1600 when T1 recorded them (78 distinct plans); regeneration only appends.
        assertTrue("only ${planVectors.size} plan vectors", planVectors.size >= 1600)
        val mismatched = planVectors.mapNotNull { vector ->
            val state = vehicle(vector["vehicle"] as Map<String, Any?>?)
            val outcome = outcome(vector["plan"] as List<Map<String, Any?>>, state)
            if (matches(outcome, vector["expect"] as Map<String, Any?>)) null
            else "${vector["input"]} [${vector["language"]}] vehicle=${vector["vehicle"]}\n" +
                "  expect=${vector["expect"]}\n  actual=$outcome"
        }
        assertTrue("${mismatched.size} mismatched, for example:\n${mismatched.take(15).joinToString("\n")}",
            mismatched.isEmpty())
    }

    @Test fun theGateCoversEveryPlanRuleMessage() {
        val errors = vectors().filter { "plan" in it }
            .mapNotNull { (it["expect"] as Map<String, Any?>)["error"] }.toSet()
        assertTrue(errors.containsAll(listOf(
            "Say a command first.", "Say at most 5 steps at a time.", "Ask for status on its own.",
            "Takeoff can only be the first step.", "Land can only be the last step.",
            "Step 1 would leave the 1–10 m altitude range.", "Step 2 would leave the 1–10 m altitude range.",
            "Step 3 would leave the 1–10 m altitude range.",
            "The drone is on the ground. Start with a takeoff height, " +
                "for example: take off to 3 m and fly forward 3 m.",
            "The drone is already flying. Remove the takeoff step.",
        )))
    }
}
