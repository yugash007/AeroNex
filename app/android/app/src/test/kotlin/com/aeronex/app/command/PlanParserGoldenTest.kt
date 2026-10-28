package com.aeronex.app.command

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.NumeralKind
import com.aeronex.app.safety.MiniJson
import com.aeronex.app.safety.PlanValidator
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The T10 gate (app/test/golden/README.md): every golden vector, through [PlanParser] (normaliser, `[unk]` policy,
 * [TranscriptInspector], [CommandSplitter], [StepParser], FlightPlan) and then [PlanValidator] against the recorded
 * vehicle, must give exactly its v0.2.0 outcome: the same steps (name, arguments, source), or the same error text.
 * `ai_required` (v0.2.0 asked its AI; v2 has none) and spec_only `rejected` accept any rejection; the spec_only
 * `[unk] hold position` must give HOLD (spec 4.9).
 *
 * 0.4.0 (spec 2026-10-15, natural voice): `plan_vectors_delta_v04.json` lists the vectors whose expectation changes,
 * applied over the frozen `plan_vectors.json`. The delta may only turn a parser rejection of a 0.4.0 phrasing
 * (spoken decimals and halves, direction verbs, comparatives) into a parsed plan; its outcome is then the
 * validator's for the recorded vehicle. "Accepted" means the parser reads a plan: for some vehicles the validator
 * still refuses it with a vehicle-state message ("The drone is on the ground ..."), and that message is the new
 * expectation. Any other change is a defect, and [theDeltaOnlyAcceptsTheNewPhrasings] fails on it.
 */
@Suppress("UNCHECKED_CAST")
class PlanParserGoldenTest {
    private val validator = PlanValidator()

    private fun golden(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (candidate in listOf("test/golden/$name", "app/test/golden/$name")) {
                val file = File(dir, candidate)
                if (file.isFile) return file
            }
            dir = dir.parentFile
        }
        error("app/test/golden/$name not found above ${System.getProperty("user.dir")}")
    }

    private fun json(name: String): Any? = MiniJson.parse(golden(name).readText(Charsets.UTF_8))

    private fun frozen(): List<Map<String, Any?>> = json("plan_vectors.json") as List<Map<String, Any?>>

    private fun delta(): List<Map<String, Any?>> = (json("plan_vectors_delta_v04.json") as Map<String, Any?>)["changes"] as List<Map<String, Any?>>

    private fun key(vector: Map<String, Any?>) = Triple(vector["input"], vector["language"], vector["vehicle"])

    /** The frozen vectors with the 0.4.0 delta applied (expect and plan replaced). */
    private fun vectors(): List<Map<String, Any?>> {
        val changes = delta().associateBy(::key)
        return frozen().map { vector ->
            val change = changes[key(vector)] ?: return@map vector
            vector + mapOf("expect" to change["expect"], "plan" to change["plan"])
        }
    }

    /** The recorded {landed, altitude} as a connected, fresh vehicle; home neutral (the T5 gate's reading). */
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

    /** The accepted steps as {name, arguments, source}, or the rejection message. */
    private fun outcome(input: String, language: String, vehicle: VehicleState?): Any =
        when (val parsed = PlanParser.parse(input, language)) {
            is ParseResult.Rejected -> parsed.message
            is ParseResult.Accepted -> validator.validate(parsed.plan, vehicle) ?: parsed.plan.steps.map {
                mapOf("name" to it.command.name, "arguments" to it.command.arguments, "source" to it.source.wireName)
            }
        }

    private fun sameArguments(actual: Map<String, Any?>, expected: Map<String, Any?>) =
        actual.keys == expected.keys && actual.all { (key, value) ->
            val other = expected[key]
            if (value is Number && other is Number) value.toDouble() == other.toDouble() else value == other
        }

    private fun matches(outcome: Any, expect: Map<String, Any?>): Boolean {
        val error = expect["error"]
        if (error == AI_REQUIRED || error == REJECTED) return outcome is String
        if (error != null) return outcome == error
        val steps = outcome as? List<Map<String, Any?>> ?: return false
        val expected = expect["steps"] as List<Map<String, Any?>>
        return steps.size == expected.size && steps.zip(expected).all { (step, want) ->
            step["name"] == want["name"] && step["source"] == want["source"] &&
                sameArguments(step["arguments"] as Map<String, Any?>, want["arguments"] as Map<String, Any?>)
        }
    }

    @Test fun everyGoldenVectorGivesItsRecordedOutcome() {
        val vectors = vectors()
        assertTrue("only ${vectors.size} vectors", vectors.size >= 3431)
        val counts = linkedMapOf("accepted" to 0, "exact error" to 0, "ai_required rejected" to 0, "spec_only" to 0)
        val mismatched = mutableListOf<String>()
        for (vector in vectors) {
            val expect = vector["expect"] as Map<String, Any?>
            val outcome = outcome(vector["input"] as String, vector["language"] as String,
                vehicle(vector["vehicle"] as Map<String, Any?>?))
            if (!matches(outcome, expect)) {
                mismatched += "${vector["input"]} [${vector["language"]}] vehicle=${vector["vehicle"]}\n" +
                    "  expect=$expect\n  actual=$outcome"
                continue
            }
            val kind = when {
                vector["spec_only"] == true -> "spec_only"
                "steps" in expect -> "accepted"
                expect["error"] == AI_REQUIRED -> "ai_required rejected"
                else -> "exact error"
            }
            counts[kind] = counts.getValue(kind) + 1
        }
        println("T10 golden gate (0.4.0 delta: ${delta().size} changes): ${vectors.size - mismatched.size}/${vectors.size} agree $counts")
        assertTrue("${mismatched.size} of ${vectors.size} mismatched, for example:\n${mismatched.take(25).joinToString("\n")}",
            mismatched.isEmpty())
    }

    /**
     * The delta is pinned to the frozen file and only accepts the 0.4.0 phrasings: each change names one frozen
     * vector, repeats its old expectation, which was a parser rejection (no `plan`), and gives a parsed `plan` whose
     * outcome under the validator is the new expectation. Its input holds a 0.4.0 word (a decimal point, a half, a
     * direction verb or a comparative).
     */
    @Test fun theDeltaOnlyAcceptsTheNewPhrasings() {
        val frozen = frozen().associateBy(::key)
        val changes = delta()
        assertEquals("a vector is changed twice", changes.size, changes.map(::key).toSet().size)
        val words = NEW_WORDS
        for (change in changes) {
            val name = "${change["input"]} [${change["language"]}] vehicle=${change["vehicle"]}"
            val old = frozen[key(change)]
            assertNotNull("$name: no such frozen vector", old)
            assertEquals("$name: was", old!!["expect"], change["was"])
            assertFalse("$name: the frozen vector was already parsed", "plan" in old)
            assertTrue("$name: was no rejection", (old["expect"] as Map<String, Any?>)["error"] is String)
            val plan = change["plan"] as List<Map<String, Any?>>
            assertTrue("$name: no plan", plan.isNotEmpty())
            val expect = change["expect"] as Map<String, Any?>
            val steps = plan.map { PlanStep(FlightCommand.of(it["name"] as String, it["arguments"] as Map<String, Any?>), StepSource.RULES, "") }
            val validated = validator.validate(FlightPlan(steps), vehicle(change["vehicle"] as Map<String, Any?>?))
            if (validated == null) {
                assertEquals("$name: expect", plan.map { it + ("source" to "rules") }, expect["steps"])
            } else {
                assertEquals("$name: expect", mapOf("error" to validated), expect)
            }
            val input = RuleIntentEngine.tokens(Normalizer.clean(change["input"] as String).lowercase())
            assertTrue("$name: no 0.4.0 phrasing", words.any { phrase -> RuleIntentEngine.removeSubsequence(input, phrase) != null })
        }
    }

    /** Completeness: every frozen vector the rules did not read (no `plan`) and the 0.4.0 parser reads is in the delta. */
    @Test fun theDeltaListsEveryVectorTheParserNowReads() {
        val listed = delta().map(::key).toSet()
        val missing = frozen().filter { "plan" !in it && it["spec_only"] != true && key(it) !in listed }
            .filter { PlanParser.parse(it["input"] as String, it["language"] as String) is ParseResult.Accepted }
            .map { "${it["input"]} [${it["language"]}] vehicle=${it["vehicle"]}" }
        assertTrue("not in plan_vectors_delta_v04.json:\n${missing.joinToString("\n")}", missing.isEmpty())
        assertTrue(delta().all { (it["phrasing"] as? String).orEmpty().isNotBlank() })
    }

    @Test fun theSpecUnkVectorsAreCovered() {
        val spec = frozen().filter { it["spec_only"] == true }.map { it["input"] }.toSet()
        assertEquals(setOf("[unk] hold position", "[unk] what is the [unk] fly"), spec)
    }

    private companion object {
        const val AI_REQUIRED = "ai_required"
        const val REJECTED = "rejected"

        /** The 0.4.0 words of every lexicon: decimal points, "and a half", halves, direction verbs, comparatives. */
        val NEW_WORDS: List<List<String>> = GrammarTables.lexicons.values.flatMap { lexicon ->
            lexicon.decimalPoints + lexicon.andHalf + lexicon.directionVerbs.values.flatten() + lexicon.comparatives.values.flatten() +
                lexicon.numerals.filter { it.kind == NumeralKind.FRACTION && ' ' !in it.phrase }.map { it.phrase }
        }.map { RuleIntentEngine.tokens(Normalizer.clean(it).lowercase()) }
    }
}
