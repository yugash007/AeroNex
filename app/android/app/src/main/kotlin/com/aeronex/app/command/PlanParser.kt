package com.aeronex.app.command

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.grammar.GrammarTables
import kotlin.math.max
import kotlin.math.min

/** What a parser made of an utterance. */
sealed interface ParseResult {
    /** A structurally valid plan (FlightPlan rules); the vehicle check is the validator's. */
    data class Accepted(val plan: FlightPlan, val transcript: String) : ParseResult

    /** Never a plan; [message] is the operator text (the v0.2.0 wording where v0.2.0 had one). */
    data class Rejected(val message: String) : ParseResult
}

/**
 * Typed or recognised text → plan, with no AI fallback (spec 4.9): [Normalizer], the [UnkPolicy], the
 * [TranscriptInspector], then the port of v0.2.0 `PlanInterpreter` ([CommandSplitter] and [StepParser] per clause).
 * A clause the rules do not read rejects the whole plan. Gated on all golden vectors (app/test/golden).
 */
object PlanParser {
    const val SAY_COMMAND = "Say a command first."
    const val TOO_MANY_STEPS = "Say at most 5 steps at a time."

    /** v2's answer where v0.2.0 asked its AI: no rule reads the clause. */
    fun notUnderstood(step: Int, clause: String) =
        "Step $step (\"$clause\"): not understood; say it again with the supported words, for example: move forward 3 m."

    /** [language] is the selected one ("te-IN", "te"): only the inspector's script check depends on it. */
    fun parse(text: String, language: String): ParseResult {
        val heard = when (val unk = UnkPolicy.check(Normalizer.normalize(text))) {
            is UnkPolicy.Result.Reject -> return ParseResult.Rejected(unk.message)
            is UnkPolicy.Result.Pass -> unk.text
        }
        val review = TranscriptInspector.inspect(heard, language)
        review.problem?.let { return ParseResult.Rejected(it) }
        return try {
            ParseResult.Accepted(interpret(review.normalized), review.normalized)
        } catch (e: CommandFormatException) {
            ParseResult.Rejected(e.message ?: SAY_COMMAND)
        }
    }

    /**
     * Typed text, or a transcript, in the selected [language], so that typed commands work in every language without
     * a speech pack (spec 4.10, 7.1). ta, kn and ml have no rule vocabulary (v0.2.0 had none): text in the selected
     * one's script is read by its templates ([CtcTemplateMatcher]). Anything else is [parse]'s.
     */
    fun parseText(text: String, language: String): ParseResult {
        val matcher = Normalizer.languageCode(language)?.let(templates::get) ?: return parse(text, language)
        val lexicon = GrammarTables.lexicon(matcher.code)
        val native = Normalizer.clean(text).codePoints().anyMatch { it in lexicon.scriptFirst..lexicon.scriptLast }
        return if (native) matcher.match(text) else parse(text, language)
    }

    /** The template readers of the languages with no rule vocabulary. */
    private val templates: Map<String, CtcTemplateMatcher> by lazy {
        (GrammarTables.CTC_LANGUAGES - GrammarTables.RULE_LANGUAGES.toSet()).associateWith(::CtcTemplateMatcher)
    }

    /** The port of v0.2.0 `PlanInterpreter.interpret` on an inspected transcript; throws [CommandFormatException]. */
    fun interpret(transcript: String): FlightPlan {
        val clauses = CommandSplitter.split(transcript)
        if (clauses.isEmpty()) throw CommandFormatException(SAY_COMMAND)
        if (clauses.size > FlightPlan.MAX_STEPS) throw CommandFormatException(TOO_MANY_STEPS)
        // Every clause meets the rules first, so a rejected clause names itself.
        val ruled = clauses.mapIndexed { i, clause -> step(i, clause) { StepParser.parse(clause) } }
        val unread = ruled.indexOf(null)
        if (unread >= 0) {
            checkUnread(ruled)
            throw CommandFormatException(notUnderstood(unread + 1, clauses[unread]))
        }
        return FlightPlan(ruled.mapIndexed { i, command -> PlanStep(command!!, StepSource.RULES, clauses[i]) })
    }

    /**
     * v0.2.0 `_checkBeforeAi`: when the read steps break the plan rules whatever the unread clauses are, that message
     * wins (with FlightPlan's wording and order). Any unread clause could reach any altitude in range.
     */
    private fun checkUnread(ruled: List<FlightCommand?>) {
        val names = ruled.map { it?.name }
        if (names.size > 1 && FlightCommand.STATUS in names) throw CommandFormatException("Ask for status on its own.")
        if (names.lastIndexOf(FlightCommand.TAKEOFF) > 0) throw CommandFormatException("Takeoff can only be the first step.")
        val land = names.indexOf(FlightCommand.LAND)
        if (land >= 0 && land != names.size - 1) throw CommandFormatException("Land can only be the last step.")
        if (names.first() != FlightCommand.TAKEOFF) return
        var low = ruled.first()!!.altitudeM!!
        var high = low
        for (i in 1 until ruled.size) {
            val command = ruled[i]
            if (command == null) {
                low = 1.0
                high = 10.0
                continue
            }
            val direction = command.direction
            if (direction != "up" && direction != "down") continue
            val change = if (direction == "up") command.distanceM!! else -command.distanceM!!
            if (high + change < 1 - 1e-9 || low + change > 10 + 1e-9) {
                throw CommandFormatException("Step ${i + 1} would leave the 1–10 m altitude range.")
            }
            low = max(low + change, 1.0)
            high = min(high + change, 10.0)
        }
    }

    private inline fun <T> step(index: Int, clause: String, read: () -> T): T = try {
        read()
    } catch (e: CommandFormatException) {
        throw CommandFormatException("Step ${index + 1} (\"$clause\"): ${e.message}")
    }
}
