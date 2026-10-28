package com.aeronex.app.ai

import java.io.File
import java.util.concurrent.Semaphore
import org.json.JSONException
import org.json.JSONObject

/** Native timings of one planner run (llama.cpp side), ms and tokens. */
data class PlannerStats(
    val loadMs: Double,
    val promptMs: Double,
    val generateMs: Double,
    val promptTokens: Int,
    val generatedTokens: Int,
)

/** The outcome of one [LfmPlanner.plan] call; [elapsedMs] is the wall time of the whole call. */
sealed interface PlannerResult {
    val elapsedMs: Long

    /** 1-5 short English single-command steps, in order (each matches the planner grammar). */
    data class Steps(val steps: List<String>, val raw: String, val stats: PlannerStats, override val elapsedMs: Long) :
        PlannerResult

    /** The model returned no steps: a negated, unclear or unsafe request. */
    data class Refused(val raw: String, val stats: PlannerStats, override val elapsedMs: Long) : PlannerResult

    /**
     * The steps name a take off, land, hold or status command the operator did not say, or leave out one they said
     * (A4 for the command itself; [message] is operator-facing).
     */
    data class Ungrounded(val message: String, val raw: String, override val elapsedMs: Long) : PlannerResult

    /** No usable plan; [message] is operator-facing. */
    data class Failed(val message: String, override val elapsedMs: Long) : PlannerResult

    data class Cancelled(override val elapsedMs: Long) : PlannerResult

    data class TimedOut(override val elapsedMs: Long) : PlannerResult
}

/**
 * The LFM2.5 planner (spec "Command path"): the (glossed) English utterance in, an ordered list of 1-5 short English
 * single-command steps out, via one native call whose output is grammar-constrained JSON `{"steps":[...]}`. The
 * steps are untrusted text: the session parses each one with the rules (or Needle) and grounds it against the
 * operator's words (A4) before anything becomes a plan.
 *
 * Threading: [plan] and [warmUp] block (run them on the AI thread, one at a time). [cancel], [unload] and
 * [unloadIfIdle] never block and may be called from any thread. The model stays loaded (mmap) between calls until
 * [unload] (onTrimMemory) or [unloadIfIdle] (A6: 120 s idle); an unload requested during a run happens when it ends.
 */
class LfmPlanner(
    private val engine: AiEngine,
    val model: File,
    val threads: Int,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val gate = Semaphore(1)
    @Volatile private var unloadRequested = false
    @Volatile private var run = 0L
    @Volatile private var cancelledRun = -1L
    @Volatile private var lastUsedMs = 0L

    /** Whether the native side may hold the model (set by a run or [warmUp], cleared by an unload). */
    @Volatile var loaded = false
        private set

    /**
     * Plans [text] within [timeoutMs] (native deadline, including a lazy load). [cancelled] is the request's own token
     * (STOP, a new PTT press, new typed input, disconnect); a result that arrives after it fires is discarded.
     */
    fun plan(text: String, timeoutMs: Int = AiBudget.PLANNER_MS, cancelled: () -> Boolean = { false }): PlannerResult {
        val started = clock()
        val input = text.trim()
        if (input.isEmpty()) return PlannerResult.Failed(EMPTY, clock() - started)
        if (input.length > MAX_INPUT_CHARS) return PlannerResult.Failed(TOO_LONG, clock() - started)
        gate.acquire()
        val id = ++run
        try {
            // reset() first, then the request's token: a cancel that lands in between still reaches the native flag.
            engine.reset()
            if (cancelled() || cancelledRun == id) return PlannerResult.Cancelled(clock() - started)
            loaded = true
            val reply = try {
                engine.plan(model.path.toByteArray(), input.toByteArray(), threads, timeoutMs)
            } catch (error: IllegalStateException) {
                return if (cancelled() || cancelledRun == id) PlannerResult.Cancelled(clock() - started)
                else PlannerResult.Failed(error.message ?: GENERIC, clock() - started)
            }
            if (cancelled() || cancelledRun == id) return PlannerResult.Cancelled(clock() - started)
            return parse(input, String(reply, Charsets.UTF_8), clock() - started)
        } finally {
            lastUsedMs = clock()
            if (unloadRequested) unloadNow()
            gate.release()
        }
    }

    /** Loads the model and evaluates the planner's fixed instructions ahead of the first command; returns ms. */
    fun warmUp(): Double {
        gate.acquire()
        try {
            loaded = true
            return engine.warmUp(model.path.toByteArray(), threads)
        } finally {
            lastUsedMs = clock()
            if (unloadRequested) unloadNow()
            gate.release()
        }
    }

    /** Cancels the running [plan] (if any) and makes a following Needle call of the same request fail. Any thread. */
    fun cancel() {
        cancelledRun = run
        engine.cancel()
    }

    /** Frees the model now, or when the running call ends. Never blocks (safe from onTrimMemory on the main thread). */
    fun unload() {
        unloadRequested = true
        if (!gate.tryAcquire()) return
        try { unloadNow() } finally { gate.release() }
    }

    /** A6: unloads after [AiBudget.IDLE_UNLOAD_MS] without use. Returns whether it unloaded. Never blocks. */
    fun unloadIfIdle(): Boolean {
        if (!loaded || clock() - lastUsedMs < AiBudget.IDLE_UNLOAD_MS) return false
        if (!gate.tryAcquire()) return false
        try {
            if (!loaded || clock() - lastUsedMs < AiBudget.IDLE_UNLOAD_MS) return false
            unloadRequested = true
            unloadNow()
            return true
        } finally { gate.release() }
    }

    /** Holding [gate]. */
    private fun unloadNow() {
        unloadRequested = false
        if (!loaded) return
        engine.unload()
        loaded = false
    }

    private fun parse(input: String, reply: String, elapsedMs: Long): PlannerResult {
        val json = try { JSONObject(reply) } catch (_: JSONException) { return PlannerResult.Failed(INVALID, elapsedMs) }
        when (json.optString("stop")) {
            "cancelled" -> return PlannerResult.Cancelled(elapsedMs)
            "timeout" -> return PlannerResult.TimedOut(elapsedMs)
        }
        if (!json.optBoolean("complete", false) || json.optString("stop") != "done") {
            return PlannerResult.Failed(INCOMPLETE, elapsedMs)
        }
        val array = json.optJSONArray("steps") ?: return PlannerResult.Failed(INVALID, elapsedMs)
        // Never truncate: dropping a sixth step would silently change what the operator asked for.
        if (array.length() > MAX_STEPS) return PlannerResult.Failed(TOO_MANY_STEPS, elapsedMs)
        val steps = (0 until array.length()).map { index ->
            val step = array.opt(index) as? String
            if (step == null || !STEP.matches(step)) return PlannerResult.Failed(INVALID, elapsedMs)
            step
        }
        val stats = PlannerStats(json.optDouble("loadMs", 0.0), json.optDouble("promptMs", 0.0),
            json.optDouble("generateMs", 0.0), json.optInt("promptTokens"), json.optInt("generatedTokens"))
        val raw = json.optString("text")
        if (steps.isEmpty()) return PlannerResult.Refused(raw, stats, elapsedMs)
        if (!commandsMatch(input, steps)) return PlannerResult.Ungrounded(NOT_WHAT_YOU_SAID, raw, elapsedMs)
        return PlannerResult.Steps(steps, raw, stats, elapsedMs)
    }

    /**
     * The commands without a number or direction to ground (host eval: "turn off the motors" became hold, "status"
     * became land, "before you land, go back" lost its land): every step naming one must match the operator's words,
     * and every one the operator named must be in a step. "wait" and "hold on" support a hold but do not demand one.
     */
    private fun commandsMatch(input: String, steps: List<String>): Boolean {
        val said = words(input)
        val asked = said.replace(" hold on ", " ")
        val planned = steps.map(::words)
        return COMMAND_WORDS.all { (phrases, triggers) ->
            val inPlan = planned.any { step -> phrases.any { " $it " in step } }
            if (inPlan) phrases.any { " $it " in said } else triggers.none { " $it " in asked }
        }
    }

    private fun words(text: String) =
        text.lowercase().split(NON_WORD).filter { it.isNotEmpty() }.joinToString(" ", prefix = " ", postfix = " ")

    companion object {
        const val MAX_STEPS = 5
        const val MAX_INPUT_CHARS = 400

        /** The planner grammar's step (lfm_planner.h): 1-8 words of lowercase letters or plain decimal numbers. */
        private const val WORD = "(?:[a-z]{1,12}|[0-9]{1,3}(?:\\.[0-9]{1,2})?)"
        val STEP = Regex("$WORD(?: $WORD){0,7}")

        private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

        /** Per command: the words that name it, and those of them that ask for it (tools/ai-eval mirrors these). */
        private val COMMAND_WORDS: List<Pair<List<String>, List<String>>> = listOf(
            listOf("take off", "taking off", "takeoff", "launch", "lift off", "liftoff").let { it to it },
            listOf("land", "landing", "touch down", "touchdown").let { it to it },
            listOf("hold", "holding", "hover", "hovering", "wait", "stay", "pause") to
                listOf("hold", "holding", "hover", "hovering"),
            listOf("status", "telemetry", "battery", "health", "report").let { it to it },
        )

        const val NOT_WHAT_YOU_SAID = "I could not match that to what you said."
        const val EMPTY = "Say a command first."
        const val TOO_LONG = "Say a shorter command."
        const val TOO_MANY_STEPS = "Say at most 5 steps at a time."
        const val INCOMPLETE = "The AI could not finish a plan. Say it again in fewer words."
        const val INVALID = "The AI answer was not a plan. Say it again."
        const val GENERIC = "The AI could not plan that command."
    }
}
