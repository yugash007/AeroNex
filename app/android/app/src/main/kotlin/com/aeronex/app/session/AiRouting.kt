package com.aeronex.app.session

import com.aeronex.app.ai.AiAvailability
import com.aeronex.app.ai.AiBudget
import com.aeronex.app.ai.AiModelStore
import com.aeronex.app.ai.LfmPlanner
import com.aeronex.app.ai.NeedleToolCaller
import com.aeronex.app.ai.PlannerResult
import com.aeronex.app.ai.ToolCallResult
import com.aeronex.app.command.DartText
import com.aeronex.app.command.Gloss
import com.aeronex.app.command.Normalizer
import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import com.aeronex.app.command.SpokenNumbers
import com.aeronex.app.command.TranscriptInspector
import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan
import com.aeronex.app.observability.Trace
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * The AI runtime the session drives (spec A5): the LFM planner and the Needle tool caller of this process. [plan],
 * [warmUp] and [call] block (the AI thread only); [cancel], [unload] and [unloadIfIdle] never block and may be called
 * anywhere.
 */
interface AiRuntime {
    fun plan(text: String, timeoutMs: Int, cancelled: () -> Boolean): PlannerResult

    fun call(step: String, cancelled: () -> Boolean, budgetMs: Long): ToolCallResult

    /** Aborts a running [plan] and makes a following [call] of the same request fail. */
    fun cancel()

    /** Whether the planner holds its model (false after an unload). */
    val loaded: Boolean get() = true

    /** Loads the planner model and evaluates its fixed instructions (blocks; no deadline, not cancellable). */
    fun warmUp() {}

    /** A6: frees the planner after [AiBudget.IDLE_UNLOAD_MS] without use. */
    fun unloadIfIdle(): Boolean = false

    /** onTrimMemory, or before the planner's model file is removed (deferred while a plan runs). */
    fun unload() {}

    companion object {
        fun of(planner: LfmPlanner, tools: NeedleToolCaller): AiRuntime = object : AiRuntime {
            override fun plan(text: String, timeoutMs: Int, cancelled: () -> Boolean) = planner.plan(text, timeoutMs, cancelled)
            override fun call(step: String, cancelled: () -> Boolean, budgetMs: Long) = tools.call(step, cancelled, budgetMs)
            override fun cancel() = planner.cancel()
            override val loaded get() = planner.loaded
            override fun warmUp() {
                planner.warmUp()
            }
            override fun unloadIfIdle() = planner.unloadIfIdle()
            override fun unload() = planner.unload()
        }
    }
}

/**
 * The phone's AI path for a [SessionController] (spec A5, A6). [availability] and [runtime] run on the AI thread only
 * (the first availability check hashes the model files once per process); [runtime] is built once AI is available.
 * [models] and [importRoot] serve the Models screen's AI rows ([AiModelSetup]). Every [idleCheckMs] the planner is
 * asked to unload when idle, or, while AI is off, availability is checked again (models copied in another way).
 */
class AiConfig(
    val availability: () -> AiAvailability,
    val runtime: () -> AiRuntime?,
    val models: AiModelStore? = null,
    /** `<external files>/import`: a model file copied to `import/<id>/<file>` can be imported from there. */
    val importRoot: File? = null,
    val threadSetup: () -> Unit = {},
    val idleCheckMs: Long = 30_000,
)

enum class AiState { IDLE, THINKING }

/** The snapshot's `ai` section; see [SessionSchema]. */
data class AiStatus(
    val available: Boolean,
    /** Why AI is not available, in plain words ([AiAvailability] reasons, or [AiRouter.CHECKING]). */
    val reason: String?,
    val state: AiState,
    /** AI requests so far: a new one means new [heard], [gloss], [understoodAs] and [error]. */
    val request: Long,
    /** What the AI was asked: the typed text or the transcript. */
    val heard: String?,
    /** Its English gloss when that differs (hi, te, ta, kn, ml words). */
    val gloss: String?,
    /** The planner's steps, e.g. "take off to 3 meters, then move forward 2 meters". */
    val understoodAs: String?,
    /** Why the AI gave no plan, or why its plan was refused. */
    val error: String?,
) {
    companion object {
        /** No AI in this session (host tests without it). */
        val OFF = AiStatus(false, null, AiState.IDLE, 0, null, null, null, null)
    }
}

/** Trace points of the AI path; the request ids start at [ID_BASE] (spans: aiGloss, aiPlan, aiTools, aiTotal). */
object AiTrace {
    const val START = "ai_start"
    const val GLOSSED = "ai_glossed"
    const val PLANNED = "ai_planned"
    const val TOOLS = "ai_tools"
    const val DONE = "ai_done"
    const val CANCELLED = "ai_cancelled"
    /** Above the voice ids ([VoiceSession.TRACE_ID_BASE]) and the supervisor's, so spans never pair across them. */
    const val ID_BASE = 2L shl 48
}

/**
 * One AI request: the rules did not understand [heard]; [stops] is the STOP count when it was said. [delivered] is set
 * (under the session's STOP order) once its plan went to the supervisor.
 */
internal class AiRequest(
    val id: Long,
    val heard: String,
    val gloss: String,
    /** The rules' answer, kept for the A6 reason when the AI turns out to be unavailable. */
    val rulesMessage: String,
    val stops: Long,
    val startedMs: Long,
) {
    val traceId get() = AiTrace.ID_BASE + id
    @Volatile var cancelled = false
    @Volatile var delivered = false
}

/** What showing an AI plan came to ([SessionController] decides, under its STOP order). */
internal sealed interface AiDelivery {
    data object Shown : AiDelivery
    data class Rejected(val message: String) : AiDelivery
    /** Cancelled or a STOP came after the request: the plan is never shown. */
    data object Discarded : AiDelivery
}

/**
 * Spec A4: a number (after number-word normalisation, sign-aware, each said number grounding one step) or a direction
 * of an AI plan must be in the operator's words or their gloss. Command kinds without a number or direction are the
 * planner's own check ([LfmPlanner]).
 */
internal object AiGrounding {
    fun check(commands: List<FlightCommand>, said: String): String? {
        val text = Normalizer.asciiDigits(SpokenNumbers.digits(Normalizer.normalize(said), GrammarTables.LANGUAGES, integers = true))
        val numbers = numbers(text)
        val directions = directions(text)
        for (command in commands) {
            val value = when (command.name) {
                FlightCommand.TAKEOFF -> command.altitudeM
                FlightCommand.MOVE -> if (command.direction in directions) command.distanceM else return LfmPlanner.NOT_WHAT_YOU_SAID
                else -> null
            } ?: continue
            val wanted = BigDecimal(value.toString())
            val at = numbers.indexOfFirst { it.compareTo(wanted) == 0 }
            if (at < 0) return LfmPlanner.NOT_WHAT_YOU_SAID
            numbers.removeAt(at)
        }
        return null
    }

    /** Every number in [text], negative after a minus word or sign ("minus two", "-2", "− 2"). */
    private fun numbers(text: String): MutableList<BigDecimal> = NUMBER.findAll(text).map { match ->
        val before = text.substring(0, match.range.first).trimEnd()
        val word = LAST_WORD.find(before)?.value?.lowercase()
        val negative = before.lastOrNull()?.let { it in DASHES } == true || word in MINUS
        BigDecimal(match.value).let { if (negative) it.negate() else it }
    }.toMutableList()

    /** The canonical directions [text] names: English direction words, direction verbs and comparatives. */
    private fun directions(text: String): Set<String> {
        val words = WORD.findAll(text.lowercase()).map { it.value }.toList()
        val found = mutableSetOf<String>()
        var i = 0
        while (i < words.size) {
            val (phrase, direction) = PHRASES.firstOrNull { (phrase, _) ->
                i + phrase.size <= words.size && phrase.indices.all { words[i + it] == phrase[it] }
            } ?: (null to null)
            if (phrase == null || direction == null) {
                i++
                continue
            }
            // "right here", "right now": no direction.
            val rightHere = phrase == listOf(RIGHT) && words.getOrNull(i + 1) in Plan.RIGHT_HERE_FOLLOWERS
            if (!rightHere) found += direction
            i += phrase.size
        }
        return found
    }

    private const val RIGHT = "right"
    private val NUMBER = Regex("""(?<![\p{L}\p{N}.])[0-9]+(?:\.[0-9]+)?""")
    private val LAST_WORD = Regex("""[\p{L}\p{M}]+$""")
    private val WORD = Regex("""[\p{L}\p{M}]+""")
    private const val DASHES = "-‐‑‒–—―−﹣－"
    private val MINUS: Set<String> = (Plan.MINUS_WORDS + GrammarTables.lexicons.values.flatMap { it.minusWords })
        .map { it.lowercase() }.toSet()

    /** English direction phrases, longest first: "back up" is backward, never up as well. */
    private val PHRASES: List<Pair<List<String>, String>> = GrammarTables.lexicon("en").let { en ->
        (en.directionVerbs.entries + en.comparatives.entries + en.directions.entries).flatMap { (direction, forms) ->
            forms.map { it.lowercase().split(' ') to direction }
        }.sortedByDescending { it.first.size }
    }
}

/**
 * The AI path of one session (spec "Command path", A1-A6): the session hands it an utterance the rules did not
 * understand ([route]); the guards and the gloss run at once on the caller's thread, the planner and the tool calls on
 * the AI thread ("aeronex-ai"), never on the link, supervisor, STOP or speech threads. A plan goes to [deliver], which
 * shows it (source AI, one CONFIRM) unless the request was cancelled. [cancel] (STOP, a new press, new text, a
 * disconnect, the background, the Cancel button) marks the request, aborts the planner and makes a late answer void.
 *
 * Warm-up (a cold load and the planner's instructions take seconds on a phone): the AI thread warms the planner when
 * AI becomes available and on [prewarm] (a push-to-talk press) after an idle unload or onTrimMemory. A request that
 * still finds it cold warms it first, within the request's total budget (A5): the planner gets what is left, and when
 * nothing is left, or it then runs out of time, the answer is [LOADING] (the planner stays loaded for the next try).
 */
internal class AiRouter(
    private val config: AiConfig,
    private val trace: Trace,
    private val clock: () -> Long,
    private val changed: () -> Unit,
    private val deliver: (AiRequest, FlightPlan) -> AiDelivery,
    private val totalMs: Long = AiBudget.TOTAL_MS,
) : AutoCloseable {
    sealed interface Routed {
        /** Not for the AI: the rules' message stands. */
        data object NotEligible : Routed
        /** For the AI, but it cannot help now: [message] is the rules' message with the plain reason. */
        data class Unavailable(val message: String) : Routed
        data class Thinking(val request: Long) : Routed
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread({
            try { config.threadSetup() } catch (_: Exception) { /* default priority */ }
            task.run()
        }, "aeronex-ai").apply { isDaemon = true }
    }
    private val ids = AtomicLong()
    private val lock = Any()
    @Volatile private var current: AiRequest? = null
    @Volatile private var runtime: AiRuntime? = null
    /** A warm-up finished and nothing asked the planner to unload since (with [AiRuntime.loaded]: warm). */
    @Volatile private var primed = false
    @Volatile private var availability = AiAvailability(false, CHECKING)
    @Volatile private var status = AiStatus.OFF.copy(reason = CHECKING)

    init {
        post { refresh() }
        try {
            executor.scheduleWithFixedDelay({
                try {
                    if (!availability.available) refresh()
                    else if (runtime?.unloadIfIdle() == true) primed = false
                } catch (_: Throwable) {
                    // Next time. Not only RuntimeExceptions (audit S11): a periodic task that throws never runs again.
                }
            }, config.idleCheckMs, config.idleCheckMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    fun status(): AiStatus = status.copy(available = availability.available, reason = availability.reason)

    /**
     * An utterance the rules rejected with [rulesMessage]. Only a rejection [aiMayRead] goes on (A2), and only when it
     * names no STOP word (A1), no negation, minus, other unit or self-correction, and the gloss read every native word.
     */
    fun route(heard: String, rulesMessage: String, stops: Long): Routed {
        if (!aiMayRead(rulesMessage) || !plannable(heard)) return Routed.NotEligible
        val startedAt = trace.now()
        val gloss = Gloss.english(heard)
        if (!gloss.complete || gloss.text.isBlank()) return Routed.NotEligible
        val known = availability
        if (!known.available && known.reason != CHECKING) {
            // Check again: models copied in another way (or a transient state) are found by the next request.
            post { refresh() }
            return Routed.Unavailable("$rulesMessage ${unavailable(known.reason ?: AiAvailability.NATIVE_FAILED)}")
        }
        val request = AiRequest(ids.incrementAndGet(), heard, gloss.text, rulesMessage, stops, clock())
        trace.mark(AiTrace.START, request.traceId, mapOf("heard" to heard), startedAt)
        trace.mark(AiTrace.GLOSSED, request.traceId, mapOf("gloss" to gloss.text))
        synchronized(lock) {
            current?.cancelled = true
            current = request
            status = AiStatus(known.available, known.reason, AiState.THINKING, request.id, heard,
                gloss.text.takeIf { it != DartText.collapseWhitespace(DartText.trim(heard)) }, null, null)
        }
        changed()
        post { run(request) }
        return Routed.Thinking(request.id)
    }

    /**
     * Cancels the request in progress (if any) and returns it; a late answer is discarded. Never blocks. When it was
     * [AiRequest.delivered] already, its plan may be pending: the caller drops that too.
     */
    fun cancel(reason: String): AiRequest? {
        val request = synchronized(lock) {
            val request = current?.takeIf { !it.cancelled } ?: return null
            request.cancelled = true
            current = null
            status = status.copy(state = AiState.IDLE)
            request
        }
        runtime?.cancel()
        trace.mark(AiTrace.CANCELLED, request.traceId, mapOf("reason" to reason))
        changed()
        return request
    }

    /** A push-to-talk press: a cold planner loads on the AI thread while the operator speaks. Never blocks. */
    fun prewarm() {
        val runtime = runtime ?: return
        if (availability.available && !(primed && runtime.loaded)) post { warmQuietly() }
    }

    /** onTrimMemory: the planner unloads now, or when its run ends. */
    fun trimMemory() {
        primed = false
        runtime?.unload()
    }

    /** The models changed (download, import, removal): check availability again on the AI thread. */
    fun modelsChanged() = post { refresh() }

    /** Before the planner's model file is removed: no request runs on it and its weights are freed. */
    fun release() {
        cancel("model removed")
        primed = false
        runtime?.unload()
    }

    override fun close() {
        cancel("closed")
        executor.shutdownNow()
    }

    // --- the AI thread ---

    private fun post(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    /** Checks availability; a runtime built now is warmed up unless [warm] is false (a request warms it itself). */
    private fun refresh(warm: Boolean = true) {
        val checked = try {
            config.availability()
        } catch (_: Exception) {
            AiAvailability(false, AiAvailability.NATIVE_FAILED)
        }
        var built = false
        if (checked.available && runtime == null) {
            runtime = try { config.runtime() } catch (_: Exception) { null }
            built = runtime != null
        } else if (!checked.available) {
            runtime?.unload()
            runtime = null
            primed = false
        }
        val next = if (checked.available && runtime == null) AiAvailability(false, AiAvailability.NATIVE_FAILED) else checked
        if (next != availability) {
            availability = next
            changed()
        }
        if (built && warm) warmQuietly()
    }

    /** Warms a cold planner; returns whether it did (false: warm already). Throws what [AiRuntime.warmUp] throws. */
    private fun warmUp(runtime: AiRuntime): Boolean {
        if (primed && runtime.loaded) return false
        primed = false
        runtime.warmUp()
        primed = true
        return true
    }

    private fun warmQuietly() {
        try {
            runtime?.let(::warmUp)
        } catch (_: Throwable) {
            // The next request warms it again and says why it failed.
        }
    }

    private fun run(request: AiRequest) {
        if (request.cancelled) return
        val outcome = try {
            refresh(warm = false)
            val runtime = runtime
            if (runtime == null) {
                Outcome.Failed("${request.rulesMessage} ${unavailable(availability.reason ?: AiAvailability.NATIVE_FAILED)}")
            } else {
                pipeline(runtime, request)
            }
        } catch (error: Throwable) {
            failed(error)
        }
        val result = when (outcome) {
            is Outcome.Cancelled -> null
            is Outcome.Failed -> outcome.message to null
            is Outcome.Planned -> when (val delivery = deliver(request, outcome.plan)) {
                AiDelivery.Discarded -> null
                AiDelivery.Shown -> null to outcome.understoodAs
                is AiDelivery.Rejected -> delivery.message to outcome.understoodAs
            }
        }
        if (result != null) trace.mark(AiTrace.DONE, request.traceId, mapOf("error" to result.first))
        synchronized(lock) {
            if (current !== request) return
            current = null
            // A cancelled or discarded request shows nothing: a STOP or new input replaced it.
            status = if (result == null || request.cancelled) status.copy(state = AiState.IDLE)
            else status.copy(state = AiState.IDLE, understoodAs = result.second, error = result.first)
        }
        changed()
    }

    /**
     * A request that threw. Not only RuntimeExceptions (audit S11): an Error, e.g. no memory for the planner, used to
     * leave the AI thinking until the next request. A RuntimeException's message is the engine's own.
     */
    private fun failed(error: Throwable): Outcome.Failed {
        if (error is InterruptedException) Thread.currentThread().interrupt()
        return Outcome.Failed(when (error) {
            is OutOfMemoryError -> OUT_OF_MEMORY
            is RuntimeException -> error.message ?: GENERIC
            else -> GENERIC
        })
    }

    private sealed interface Outcome {
        data class Planned(val plan: FlightPlan, val understoodAs: String) : Outcome
        data class Failed(val message: String) : Outcome
        data object Cancelled : Outcome
    }

    /**
     * A cold planner's warm-up, the planner, then per step the rules or Needle, then grounding and the plan rules
     * (spec "Command path").
     */
    private fun pipeline(runtime: AiRuntime, request: AiRequest): Outcome {
        val deadline = request.startedMs + totalMs
        val cancelled = { request.cancelled }
        val loading = warmUp(runtime)
        if (request.cancelled) return Outcome.Cancelled
        val tooSlow = if (loading) LOADING else TOO_SLOW
        val plannerMs = min(AiBudget.PLANNER_MS.toLong(), deadline - clock())
        if (plannerMs <= 0) return Outcome.Failed(tooSlow)
        val steps = when (val planned = runtime.plan(request.gloss, plannerMs.toInt(), cancelled)) {
            is PlannerResult.Steps -> planned.steps
            is PlannerResult.Refused -> return Outcome.Failed(REFUSED)
            is PlannerResult.Ungrounded -> return Outcome.Failed(planned.message)
            is PlannerResult.Failed -> return Outcome.Failed(planned.message)
            is PlannerResult.Cancelled -> return Outcome.Cancelled
            is PlannerResult.TimedOut -> return Outcome.Failed(tooSlow)
        }
        if (request.cancelled) return Outcome.Cancelled
        trace.mark(AiTrace.PLANNED, request.traceId, mapOf("steps" to steps))
        val commands = steps.mapIndexed { i, step ->
            when (val read = readStep(runtime, request, i, step, deadline)) {
                is StepRead.Command -> read.command
                is StepRead.Stop -> return read.outcome
            }
        }
        trace.mark(AiTrace.TOOLS, request.traceId)
        if (request.cancelled) return Outcome.Cancelled
        if (clock() > deadline) return Outcome.Failed(TOO_SLOW)
        AiGrounding.check(commands, request.gloss)?.let { return Outcome.Failed(it) }
        val plan = try {
            FlightPlan(commands.mapIndexed { i, command -> PlanStep(command, StepSource.AI, steps[i]) })
        } catch (e: CommandFormatException) {
            return Outcome.Failed(e.message ?: GENERIC)
        }
        return Outcome.Planned(plan, steps.joinToString(", then "))
    }

    private sealed interface StepRead {
        data class Command(val command: FlightCommand) : StepRead
        data class Stop(val outcome: Outcome) : StepRead
    }

    /** One planner step: the rules first; only a step they do not understand goes to Needle. */
    private fun readStep(runtime: AiRuntime, request: AiRequest, index: Int, step: String, deadline: Long): StepRead {
        when (val ruled = PlanParser.parse(step, ENGLISH)) {
            is ParseResult.Accepted -> {
                val only = ruled.plan.steps.singleOrNull()?.command
                    ?: return StepRead.Stop(Outcome.Failed(stepMessage(index, step, ONE_COMMAND)))
                return StepRead.Command(only)
            }
            is ParseResult.Rejected -> if (!notUnderstood(ruled.message)) {
                return StepRead.Stop(Outcome.Failed(stepMessage(index, step, STEP_PREFIX.replaceFirst(ruled.message, ""))))
            }
        }
        val budget = min(AiBudget.NEEDLE_STEP_MS, deadline - clock())
        if (budget <= 0) return StepRead.Stop(Outcome.Failed(TOO_SLOW))
        return when (val call = runtime.call(step, { request.cancelled }, budget)) {
            is ToolCallResult.Call ->
                if (call.command.name == kindOf(step)) StepRead.Command(call.command)
                else StepRead.Stop(Outcome.Failed(LfmPlanner.NOT_WHAT_YOU_SAID))
            is ToolCallResult.NoCall -> StepRead.Stop(Outcome.Failed(stepMessage(index, step, NOT_READ)))
            is ToolCallResult.Failed -> StepRead.Stop(Outcome.Failed(call.message))
            is ToolCallResult.Cancelled -> StepRead.Stop(Outcome.Cancelled)
            is ToolCallResult.TimedOut -> StepRead.Stop(Outcome.Failed(TOO_SLOW))
        }
    }

    companion object {
        const val CHECKING = "Checking the AI models"
        const val REFUSED = "The AI found no command it may send in that. Say it again with the supported words."
        const val TOO_SLOW = "The AI took too long. Say it again in fewer words, or with the supported words."
        const val GENERIC = "The AI could not plan that command."
        const val OUT_OF_MEMORY = "The AI ran out of memory. Close other apps, then say the command again."
        const val LOADING = "The AI was loading. It is ready now: say the command again."
        private const val ONE_COMMAND = "say one command per step."
        private const val NOT_READ = "not understood by the AI."
        private const val ENGLISH = "en"

        /** The plain A6 reason after a rules message: "AI is off: Needs a 64-bit ARM phone." */
        fun unavailable(reason: String) = "AI is off: $reason."

        fun stepMessage(index: Int, step: String, reason: String) = "AI step ${index + 1} (\"$step\"): $reason"

        private val STEP_PREFIX = Regex("""^Step \d+ \(".*?"\): """)
        private val NOT_UNDERSTOOD_SUFFIX = "\"): " + PlanParser.notUnderstood(1, "").substringAfter("\"): ")

        /** The rules' generic "not understood" (v0.2.0 `ai_required`). */
        fun notUnderstood(message: String) = message.startsWith("Step ") && message.endsWith(NOT_UNDERSTOOD_SUFFIX)

        /** The rules' reason for a step with more than one number or a scale word. */
        const val PLAIN_NUMBER = "say a plain number, for example: 2.5 m."

        /**
         * The rule rejections the AI may take (A2): the generic "not understood" and a step with more than one number
         * ("after taking off to 6 meters move left 4 meters"), which the planner splits into steps. Grounding and the
         * range rules still apply to every number it keeps.
         */
        fun aiMayRead(message: String) = notUnderstood(message) ||
            message.startsWith("Step ") && STEP_PREFIX.replaceFirst(message, "") == PLAIN_NUMBER

        /**
         * A1/A2 guards on the utterance itself: a STOP word, a negation, a minus sign or word, another unit, or a
         * self-correction ("3 meters, I mean 4"), which must be said again rather than guessed.
         */
        fun plannable(heard: String): Boolean {
            val text = Normalizer.normalize(heard).lowercase()
            if (TranscriptInspector.negated(text)) return false
            val words = WORD_BREAK.split(text).filter { it.isNotEmpty() }
            val joined = words.joinToString(" ", " ", " ")
            if (STOP_WORDS.any { " $it " in joined }) return false
            if (words.any { it in MINUS_WORDS || it in OTHER_UNITS } || SIGNED.containsMatchIn(text)) return false
            if (words.any { it in CORRECTION_WORDS }) return false
            return true
        }

        /** The command kind a planner step names, which a Needle call for it must have. */
        fun kindOf(step: String): String {
            val words = WORD_BREAK.split(step.lowercase()).filter { it.isNotEmpty() }.joinToString(" ", " ", " ")
            return KINDS.firstOrNull { (_, phrases) -> phrases.any { " $it " in words } }?.first ?: FlightCommand.MOVE
        }

        private val WORD_BREAK = Regex("""[^\p{L}\p{M}\p{N}]+""")
        private val SIGNED = Regex("""(?<!\p{Nd})[-\x{2010}-\x{2015}\x{2212}\x{FE63}\x{FF0D}]\s*\p{Nd}""")
        private val STOP_WORDS: Set<String> = GrammarTables.lexicons.values.flatMap { it.stopLane }
            .map { Normalizer.clean(it).lowercase() }.toSet()
        private val MINUS_WORDS: Set<String> = (Plan.MINUS_WORDS + GrammarTables.lexicons.values.flatMap { it.minusWords })
            .map { Normalizer.clean(it).lowercase() }.toSet()
        private val OTHER_UNITS: Set<String> = (Plan.OTHER_UNITS + GrammarTables.lexicons.values.flatMap { it.otherUnits })
            .map { Normalizer.clean(it).lowercase() }.toSet()
        private val CORRECTION_WORDS = setOf("mean", "meant", "actually", "sorry", "instead", "correction", "rather", "scratch")
        private val KINDS = listOf(
            FlightCommand.TAKEOFF to listOf("take off", "takeoff", "launch", "lift off", "liftoff"),
            FlightCommand.LAND to listOf("land", "landing", "touch down", "touchdown"),
            FlightCommand.HOLD to listOf("hold", "hover", "wait", "stay", "pause"),
            FlightCommand.STATUS to listOf("status", "telemetry", "battery", "health", "report"),
        )
    }
}

