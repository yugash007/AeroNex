package com.aeronex.app.ai

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import java.io.File
import org.json.JSONException
import org.json.JSONObject

/** The outcome of one [NeedleToolCaller.call]; [elapsedMs] is the wall time of the call. */
sealed interface ToolCallResult {
    val elapsedMs: Long

    /** One tool call, validated by the same allowlist and limits as every other command ([FlightCommand]). */
    data class Call(val command: FlightCommand, override val elapsedMs: Long) : ToolCallResult

    /**
     * No call. [reason]: Needle's or the native mapper's (`no_call`, `negated`, `ungrounded`, `suppressed`,
     * `several_calls`, `malformed`, `number_not_in_step`, `number_mismatch`, `status_unrelated`) or [INVALID_CALL]
     * when [FlightCommand] rejects the call.
     */
    data class NoCall(val reason: String, override val elapsedMs: Long) : ToolCallResult

    /** The runtime failed; [message] is operator-facing. */
    data class Failed(val message: String, override val elapsedMs: Long) : ToolCallResult

    data class Cancelled(override val elapsedMs: Long) : ToolCallResult

    /** The call took longer than its step budget; its answer is discarded. */
    data class TimedOut(override val elapsedMs: Long) : ToolCallResult

    companion object {
        const val INVALID_CALL = "invalid_call"
    }
}

/**
 * Cactus Needle as the tool caller (spec "Command path"): one native call per planner step, at most one
 * `{name, arguments}` back. Needle takes one command at a time; the LFM planner has already split the utterance.
 * The answer is mapped natively (needle_result.h: Needle's own negation/grounding flags, one call, a number argument
 * must be the step's only number) and validated here again with [FlightCommand.fromToolCalls]. The session still
 * grounds the call against the operator's words (A4) and validates the whole plan.
 *
 * [call] blocks (AI thread). Needle has no abort: [cancelled] is checked before and after the native call, and a
 * call over its budget is discarded. The Needle model stays mapped for the process lifetime (upstream has no unload).
 */
class NeedleToolCaller(
    private val engine: AiEngine,
    val model: File,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    fun call(
        step: String,
        cancelled: () -> Boolean = { false },
        budgetMs: Long = AiBudget.NEEDLE_STEP_MS,
    ): ToolCallResult {
        val started = clock()
        val input = step.trim()
        if (input.isEmpty() || input.length > MAX_STEP_CHARS) return ToolCallResult.Failed(INVALID_STEP, clock() - started)
        if (cancelled()) return ToolCallResult.Cancelled(clock() - started)
        val reply = try {
            engine.toolCall(model.path.toByteArray(), input.toByteArray())
        } catch (error: IllegalStateException) {
            return if (cancelled()) ToolCallResult.Cancelled(clock() - started)
            else ToolCallResult.Failed(error.message ?: GENERIC, clock() - started)
        }
        val elapsed = clock() - started
        if (cancelled()) return ToolCallResult.Cancelled(elapsed)
        if (elapsed > budgetMs) return ToolCallResult.TimedOut(elapsed)
        return parse(String(reply, Charsets.UTF_8), elapsed)
    }

    /** Makes the running request's next Needle call fail (the shared native cancel flag). Any thread. */
    fun cancel() = engine.cancel()

    private fun parse(reply: String, elapsedMs: Long): ToolCallResult {
        val json = try { JSONObject(reply) } catch (_: JSONException) { return ToolCallResult.Failed(UNREADABLE, elapsedMs) }
        if (!json.has("call")) return ToolCallResult.Failed(UNREADABLE, elapsedMs)
        if (json.isNull("call")) return ToolCallResult.NoCall(json.optString("reason").ifEmpty { "no_call" }, elapsedMs)
        val invalid = ToolCallResult.NoCall(ToolCallResult.INVALID_CALL, elapsedMs)
        val call = json.optJSONObject("call") ?: return invalid
        val name = call.opt("name") as? String ?: return invalid
        val arguments = call.optJSONObject("arguments") ?: return invalid
        val values = LinkedHashMap<String, Any>()
        for (key in arguments.keys()) {
            when (val value = arguments.get(key)) {
                is String, is Number, is Boolean -> values[key] = value
                else -> return invalid
            }
        }
        return try {
            ToolCallResult.Call(FlightCommand.fromToolCalls(listOf(mapOf("name" to name, "arguments" to values))),
                elapsedMs)
        } catch (_: CommandFormatException) {
            invalid
        }
    }

    companion object {
        const val MAX_STEP_CHARS = 120
        const val INVALID_STEP = "The AI step is empty or too long."
        const val UNREADABLE = "The tool-call model gave an unreadable answer."
        const val GENERIC = "The tool-call model failed."
    }
}
