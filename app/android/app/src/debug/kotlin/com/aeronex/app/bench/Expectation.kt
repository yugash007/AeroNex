package com.aeronex.app.bench

import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.model.FlightCommand

/** How a push-to-talk clip ended, judged against its phrase (spec 4.6: command exact match and false accepts). */
enum class Verdict {
    /** The exact command (STOP: a stop token or the hold plan; a negated phrase: rejected). */
    EXACT,
    /** A plan other than the phrase's, or any plan for a negated phrase: the dangerous case. */
    FALSE_ACCEPT,
    /** No plan ("say again"): a safe miss. */
    REJECTED,
    /** A partial stop token fired on a phrase that is not a stop: a benign hover (spec 4.12). */
    FALSE_STOP,
}

/** The voice stop spotter on one clip. */
enum class SpotterVerdict { HIT, MISS, FALSE_FIRE, QUIET }

/**
 * The expected outcome of each eSpeak phrase id (`clips-espeak/index.json`): `takeoff_<m>`, `<direction>_<m>`,
 * `land`, `hold`, `stop` (a STOP: the parser reads a spoken stop as stop-and-hold) and `neg_*` (must be rejected).
 */
object Expectation {
    private sealed interface Expected {
        data class Plan(val name: String, val arguments: Map<String, Any>) : Expected
        data object Stop : Expected
        data object Reject : Expected
    }

    private val MEASURED = Regex("([a-z]+)_(\\d+)")

    private fun expected(id: String): Expected? {
        if (id.startsWith("neg_")) return Expected.Reject
        when (id) {
            "stop" -> return Expected.Stop
            "land" -> return Expected.Plan(FlightCommand.LAND, emptyMap())
            "hold" -> return Expected.Plan(FlightCommand.HOLD, emptyMap())
        }
        val (word, metres) = MEASURED.matchEntire(id)?.destructured ?: return null
        val value = metres.toInt()
        return when (word) {
            "takeoff" -> Expected.Plan(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to value))
            in FlightCommand.DIRECTIONS ->
                Expected.Plan(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to word, FlightCommand.DISTANCE to value))
            else -> null
        }
    }

    /** [stopToken]: a partial stop token ended PTT; [parsed]: the parser's answer to the final result (null: none). */
    fun verdict(id: String, stopToken: Boolean, parsed: ParseResult?): Verdict? {
        val expected = expected(id) ?: return null
        if (stopToken) return if (expected == Expected.Stop) Verdict.EXACT else Verdict.FALSE_STOP
        val accepted = parsed as? ParseResult.Accepted
            ?: return if (expected == Expected.Reject) Verdict.EXACT else Verdict.REJECTED
        val wanted = when (expected) {
            Expected.Reject -> return Verdict.FALSE_ACCEPT
            Expected.Stop -> Expected.Plan(FlightCommand.HOLD, emptyMap())
            is Expected.Plan -> expected
        }
        val step = accepted.plan.steps.singleOrNull()?.command ?: return Verdict.FALSE_ACCEPT
        return if (step.name == wanted.name && same(step.arguments, wanted.arguments)) Verdict.EXACT else Verdict.FALSE_ACCEPT
    }

    fun spotterVerdict(id: String, detections: Int): SpotterVerdict = when {
        id == "stop" && detections > 0 -> SpotterVerdict.HIT
        id == "stop" -> SpotterVerdict.MISS
        detections > 0 -> SpotterVerdict.FALSE_FIRE
        else -> SpotterVerdict.QUIET
    }

    private fun same(actual: Map<String, Any>, wanted: Map<String, Any>): Boolean =
        actual.keys == wanted.keys && wanted.all { (key, value) ->
            val other = actual[key]
            if (value is Number && other is Number) value.toDouble() == other.toDouble() else value == other
        }
}
