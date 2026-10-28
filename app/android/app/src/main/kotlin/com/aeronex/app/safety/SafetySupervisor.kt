package com.aeronex.app.safety

import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.mavlink.CommandAck
import com.aeronex.app.link.mavlink.ExtendedSysState
import com.aeronex.app.link.mavlink.GlobalPositionInt
import com.aeronex.app.link.mavlink.LocalPositionNed
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.memory.FlightJournal
import com.aeronex.app.memory.MemoryRoutes
import com.aeronex.app.memory.Route
import com.aeronex.app.observability.Trace
import com.aeronex.app.observability.TracePoint
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import com.aeronex.app.vehicle.executor.ArduPilotExecutor
import com.aeronex.app.vehicle.executor.ExecutorTiming
import com.aeronex.app.vehicle.executor.ModeWatch
import com.aeronex.app.vehicle.executor.StepUpdate
import com.aeronex.app.vehicle.executor.StopAction
import com.aeronex.app.vehicle.executor.StopLane
import com.aeronex.app.vehicle.executor.StopProgress
import com.aeronex.app.vehicle.executor.VehicleEvents
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single owner of plan, confirmation, execution and STOP state (spec 3.3, 3.4, 4.10, 4.12, 4.14, 8.2): an actor
 * on one thread. Every public method may be called from any thread and only posts to it, so a STOP handler on the
 * main thread does no work; the futures are for callers that need the answer (the service channel, tests).
 *
 * - [submit]: STOP, HOLD and status run at once. Any other plan is validated ([PlanValidator]) and becomes a
 *   [PendingPlan]: a one-use token valid for 15 s, invalidated by new input, [cancel], [stop], [invalidatePending]
 *   (backgrounding), a disconnect or link loss, a readiness change, or switching execution.
 * - [confirm]: dry run reports every step NOT SENT and never touches the link. Live, each step is re-validated
 *   against a fresh [VehicleState] and run by the [ArduPilotExecutor]; the plan aborts (the step UNKNOWN, the rest
 *   SKIPPED, nothing replayed) on an external mode change, a stale link, a failsafe or fence STATUSTEXT, the 30 s
 *   step deadline, a disconnect or STOP.
 * - [stop]: the spec 4.12 table ([StopPolicy]) on the [StopLane]. [cancel] while a plan runs is STOP; it never
 *   disconnects. The decision counts the running plan's own mode change or ARM still in flight ([ModeWatch]). ACKs
 *   queued before a STOP's first send are dropped (they answer earlier commands), and after its ACK the lane checks
 *   the next heartbeats; until that check ends no plan may be submitted or confirmed, and a STOP press that would
 *   send nothing leaves it running. A STOP whose mode a pilot or the autopilot replaced ends at once, alarmed.
 * - The alarm of an unconfirmed STOP stays raised through later STOPs that send nothing, until [acknowledgeAlarm]
 *   or a STOP that sends again.
 * - Every step outcome is traced ([TracePoint.STEP_OUTCOME]), and every shown plan's end ([TracePoint.PLAN_END]).
 * - POSITION_TARGET_LOCAL_NED (the move echo) streams while a live plan with a move waits for confirmation or still
 *   has a move to run, so a move's echo is the stream's next sample rather than its first, which ArduCopter sends
 *   one or two intervals after the request (spec 5 M2). It stays on between moves and goes off after the last move,
 *   and on abort, STOP, invalidation, expiry, execution off and disconnect. A dry run never streams. A flight-memory
 *   flight counts as a move.
 * - Flight memory (spec 2026-10-28): the [journal] follows every step telemetry confirmed (where a takeoff, move or
 *   flight-memory flight ended) and records every finished plan. A flight-memory flight is resolved from it when the
 *   step starts, after the remaining steps were validated against it. "Mark point n" alone is saved at once, like
 *   status; inside a plan it is saved when its turn comes. The AI path never uses the flight memory.
 *
 * Wire it as: `store.accept` for link frames (the supervisor listens to the store itself), [onLinkState] for link
 * state, [attach] after each (re)connect and [detach] before closing the link. [clock] is monotonic ms (the store's).
 */
class SafetySupervisor(
    private val store: VehicleStateStore,
    private val validator: PlanValidator = PlanValidator(),
    val trace: Trace = Trace(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val pendingTtlMs: Long = PENDING_TTL_MS,
    private val tickMs: Long = TICK_MS,
    /** Written on the supervisor thread only; anyone may read [FlightJournal.state]. */
    val journal: FlightJournal = FlightJournal(),
) : AutoCloseable {
    private val actor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "aeronex-supervisor").apply { isDaemon = true }
    }
    private val events = VehicleEvents()
    private val turnQueued = AtomicBoolean(false)
    private val storeListener: (VehicleState) -> Unit = { state ->
        events.onState(state)
        if (turnQueued.compareAndSet(false, true)) post {
            turnQueued.set(false)
            turn()
        }
    }
    private val listeners = CopyOnWriteArrayList<(SupervisorState) -> Unit>()

    /** The latest published state; listeners get each new one on the supervisor thread. */
    @Volatile var state = SupervisorState()
        private set

    // Supervisor thread only.
    private var link: LinkSession? = null
    private var timing = ExecutorTiming.WIFI
    private val watch = ModeWatch(store)
    private var execution = false
    private var pending: Pending? = null
    private var run: PlanRun? = null
    private var holdStep: PlanStep? = null
    private var stopLane: StopLane? = null
    private var stopTrigger = StopTrigger.BUTTON
    private var stopId = 0L
    private var stopOverride = false
    private var stopStatus: StopStatus? = null
    /** The STOP state the operator acknowledged: its alarm stays silent until the STOP escalates (audit F4). */
    private var acknowledged: Triple<Long, StopPhase, StopAction?>? = null
    private var stage = PlanStage.IDLE
    private var plan: FlightPlan? = null
    private var source: PlanSource? = null
    private var outcomes: List<StepOutcome> = emptyList()
    private var result: String? = null
    private var notice: String? = null
    private var ticker: ScheduledFuture<*>? = null
    private var nextId = 0L
    private var lastOwned: Boolean? = null
    /** POSITION_TARGET_LOCAL_NED streaming requested on [link] for a plan with a move. */
    private var streaming = false

    init {
        require(pendingTtlMs > 0 && tickMs > 0) { "Supervisor timings must be positive" }
        store.addListener(storeListener)
    }

    /** A new connection: dry run again (S6), nothing owned, nothing pending. */
    fun attach(link: LinkSession, timing: ExecutorTiming = ExecutorTiming.forProfile(link.profile)): Future<Unit> = call {
        detachNow()
        this.link = link
        this.timing = timing
    }

    /** Before the link closes: aborts a running plan (UNKNOWN), invalidates confirmation, ends execution. */
    fun detach(): Future<Unit> = call { detachNow() }

    /** From the link's [com.aeronex.app.link.LinkListener.onLinkState]. */
    fun onLinkState(up: Boolean) {
        val at = trace.now()
        post {
            trace.mark(if (up) TracePoint.LINK_UP else TracePoint.LINK_DOWN, atNanos = at)
            if (!up) {
                invalidate(LINK_LOST)
                activeRun()?.let { abort(it, ArduPilotExecutor.LOST) }
            }
        }
    }

    /** Live execution for this connection only; ignored while no link is attached. Off during a run is STOP. */
    fun setExecution(enabled: Boolean): Future<Unit> = call {
        if (enabled == execution || enabled && link == null) return@call
        invalidate(EXECUTION_CHANGED)
        if (!enabled && activeRun() != null) doStop(StopTrigger.EXECUTION_OFF, false, trace.now())
        execution = enabled
    }

    /** A plan from the rules, the tap pad or a client. The AI source and AI steps are refused ([submitAi] only). */
    fun submit(plan: FlightPlan, source: PlanSource): Future<SubmitResult> = call {
        if (source == PlanSource.AI || plan.steps.any { it.source == StepSource.AI }) SubmitResult.Rejected(AI_ONLY_IN_PROCESS)
        else doSubmit(plan, source)
    }

    /**
     * Spec A3: a plan of the in-process AI pipeline (every step [StepSource.AI]). It is never run at once: a single
     * HOLD or status step is refused (A1: those run at once only from the rules), and anything else is validated and
     * waits for one CONFIRM like any plan.
     */
    fun submitAi(plan: FlightPlan): Future<SubmitResult> = call {
        val only = plan.steps.singleOrNull()?.command?.name
        when {
            plan.steps.any { it.source != StepSource.AI } -> SubmitResult.Rejected(AI_ONLY_IN_PROCESS)
            plan.steps.any { it.command.name in FlightCommand.MEMORY } -> SubmitResult.Rejected(AI_NO_MEMORY)
            only == FlightCommand.HOLD || only == FlightCommand.STATUS -> SubmitResult.Rejected(AI_NO_IMMEDIATE)
            else -> doSubmit(plan, PlanSource.AI)
        }
    }

    fun confirm(token: String): Future<ConfirmResult> {
        val at = trace.now()
        return call { doConfirm(token, at) }
    }

    /** STOP while a plan runs (S5); otherwise drops the pending plan. Never disconnects. */
    fun cancel() {
        val at = trace.now()
        post {
            if (activeRun() != null) doStop(StopTrigger.CANCEL, false, at)
            else if (pending != null) {
                invalidate(CANCELLED)
            }
        }
    }

    /** Any STOP trigger; [heldOneSecond] is the on-screen button's deliberate hold (the EXTERNAL override). */
    fun stop(trigger: StopTrigger, heldOneSecond: Boolean = false) {
        val at = trace.now()
        post { doStop(trigger, heldOneSecond, at) }
    }

    /** E.g. the app went to the background: the confirmation no longer applies. */
    fun invalidatePending(reason: String) = post { invalidate(reason) }

    /** The operator saw the STOP alarm: silent until the STOP changes phase or action (a new escalation). */
    fun acknowledgeAlarm() = post {
        val stop = stopStatus ?: return@post
        acknowledged = Triple(stop.id, stop.phase, stop.action)
        stopStatus = stop.copy(alarm = false)
    }

    fun addListener(listener: (SupervisorState) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (SupervisorState) -> Unit) {
        listeners -= listener
    }

    override fun close() {
        store.removeListener(storeListener)
        actor.shutdownNow()
    }

    private fun post(block: () -> Unit) {
        try {
            actor.execute {
                block()
                settle()
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
        }
    }

    private fun <T> call(block: () -> T): Future<T> = try {
        actor.submit(Callable { block().also { settle() } })
    } catch (e: RejectedExecutionException) {
        CompletableFuture.failedFuture(e)
    }

    /** After every turn of the actor. */
    private fun settle() {
        updateStreaming()
        publish()
    }

    /** See the class notes: on while a live plan still has a move to show or run. */
    private fun updateStreaming() {
        val live = link
        val wanted = live != null && execution &&
            (pending?.plan?.plan?.hasMove() == true || activeRun()?.movesLeft() == true)
        if (wanted == streaming) return
        streaming = wanted
        // The link keeps the setting even when the request is not queued, and requests it again on reconnect.
        live?.setPositionTargetStreaming(wanted)
    }

    private fun activeRun() = run?.takeIf { it.active }

    // --- submit and confirm ---

    private fun doSubmit(submitted: FlightPlan, from: PlanSource): SubmitResult {
        val now = clock()
        val vehicle = store.snapshot()
        invalidate(null) // new input
        val only = submitted.steps.singleOrNull()
        if (only?.command?.name == FlightCommand.STATUS) return SubmitResult.Immediate(status(only, vehicle, from))
        if (only?.command?.name == FlightCommand.HOLD) return SubmitResult.Immediate(hold(only, from))
        if (only?.command?.name == FlightCommand.MARK_POINT) return SubmitResult.Immediate(markNow(only, vehicle, from))
        if (activeRun() != null) return SubmitResult.Rejected(BUSY)
        if (stopLane != null) return SubmitResult.Rejected(STOP_IN_PROGRESS)
        if (execution) ownershipBlock(vehicle)?.let { return SubmitResult.Rejected(it) }
        journal.observeHome(vehicle.home)
        validator.validate(submitted, vehicle, journal.state)?.let { return SubmitResult.Rejected(it) }
        val id = ++nextId
        trace.mark(TracePoint.PLAN_VALIDATED, id, mapOf("plan" to submitted.toString(), "source" to from))
        val shown = PendingPlan(UUID.randomUUID().toString(), submitted, from, now + pendingTtlMs)
        val expiry = actor.schedule({ expire(shown) }, pendingTtlMs, TimeUnit.MILLISECONDS)
        pending = Pending(shown, readiness(vehicle), expiry)
        show(submitted, from, PlanStage.AWAITING_CONFIRMATION, submitted.steps.map { StepOutcome(it, StepStatus.PENDING) })
        notice = null
        return SubmitResult.AwaitingConfirmation(shown)
    }

    private fun doConfirm(token: String, atNanos: Long): ConfirmResult {
        val waiting = pending?.plan ?: return ConfirmResult.Rejected(NO_PLAN)
        if (waiting.token != token) return ConfirmResult.Rejected(TOKEN_MISMATCH)
        clearPending()
        if (stopLane != null) {
            dropShownPlan(STOP_IN_PROGRESS)
            return ConfirmResult.Rejected(STOP_IN_PROGRESS)
        }
        if (clock() >= waiting.expiresAtMs) {
            dropShownPlan(EXPIRED)
            return ConfirmResult.Rejected(EXPIRED)
        }
        val id = ++nextId
        trace.mark(TracePoint.CONFIRM, id, mapOf("execution" to execution), atNanos)
        val confirmed = waiting.plan
        val live = link
        if (!execution || live == null) {
            val dry = confirmed.steps.map { StepOutcome.dryRun(it) }
            dry.forEachIndexed { i, outcome -> traceOutcome(id, i, outcome) }
            finish(id, PlanStage.FINISHED, dry)
            return ConfirmResult.Started
        }
        val started = PlanRun(id, confirmed,
            ArduPilotExecutor(live, store, timing, trace, id, watch, { streaming }, events::heartbeatsAtArrival))
        run = started
        show(confirmed, waiting.source, PlanStage.EXECUTING, started.outcomes.toList())
        updateStreaming() // on before the first frame, if submitting did not already turn it on
        ensureTicking()
        startStep(started, 0, store.snapshot(), clock())
        return ConfirmResult.Started
    }

    private fun status(step: PlanStep, vehicle: VehicleState, from: PlanSource): StepOutcome {
        val live = link
        val id = ++nextId
        val outcome = when {
            !execution || live == null -> StepOutcome.dryRun(step)
            else -> when (val update = ArduPilotExecutor(live, store, timing, trace, id, watch,
                heartbeatsAtArrival = events::heartbeatsAtArrival).start(step.command, vehicle, clock())) {
                is StepUpdate.Completed -> StepOutcome.completed(step, update.detail)
                is StepUpdate.Rejected -> StepOutcome.rejected(step, update.reason)
                else -> StepOutcome.rejected(step, ArduPilotExecutor.STALE)
            }
        }
        traceOutcome(id, 0, outcome)
        if (activeRun() == null && holdStep == null) {
            finish(id, PlanStage.FINISHED, listOf(outcome), FlightPlan(listOf(step)), from)
        }
        return outcome
    }

    /** "Mark point n" alone: saved at once, like status, since it only reads telemetry (spec 2026-10-28 M4). */
    private fun markNow(step: PlanStep, vehicle: VehicleState, from: PlanSource): StepOutcome {
        val id = ++nextId
        val outcome = if (!execution || link == null) StepOutcome.dryRun(step) else mark(step, vehicle)
        traceOutcome(id, 0, outcome)
        if (activeRun() == null && holdStep == null) {
            finish(id, PlanStage.FINISHED, listOf(outcome), FlightPlan(listOf(step)), from)
        }
        return outcome
    }

    /** Saves the vehicle's position as the step's point: only a fresh, EKF-backed position with home known. */
    private fun mark(step: PlanStep, vehicle: VehicleState): StepOutcome {
        val position = vehicle.position?.takeIf { vehicle.connected && vehicle.fresh(LocalPositionNed.messageId) }
            ?: return StepOutcome.rejected(step, ArduPilotExecutor.STALE)
        if (!vehicle.positionReady) return StepOutcome.rejected(step, ArduPilotExecutor.POSITION_NOT_READY)
        val home = vehicle.home ?: return StepOutcome.rejected(step, MARK_NEEDS_HOME)
        journal.observeHome(home)
        val point = step.command.point!!
        journal.markPoint(point, position)
        val altitude = vehicle.altitudeRelM?.takeIf { vehicle.fresh(GlobalPositionInt.messageId) }
        val detail = "point $point saved, ${metres(position.horizontalDistanceTo(home))} m from home" +
            (altitude?.let { ", ${metres(it)} m up" } ?: "")
        return StepOutcome.completed(step, detail)
    }

    /** HOLD is the STOP action (spec 4.12). */
    private fun hold(step: PlanStep, from: PlanSource): StepOutcome {
        if (!execution || link == null) {
            val outcome = StepOutcome.dryRun(step)
            val id = ++nextId
            traceOutcome(id, 0, outcome)
            if (activeRun() == null) finish(id, PlanStage.FINISHED, listOf(outcome), FlightPlan(listOf(step)), from)
            return outcome
        }
        val running = activeRun() != null
        if (!running) {
            show(FlightPlan(listOf(step)), from, PlanStage.EXECUTING, listOf(StepOutcome(step, StepStatus.RUNNING)))
            holdStep = step
        }
        doStop(StopTrigger.HOLD_COMMAND, false, trace.now())
        val stop = stopStatus!!
        return holdOutcome(step, stop)
    }

    // --- plan execution ---

    private fun startStep(current: PlanRun, index: Int, vehicle: VehicleState, now: Long) {
        val steps = current.plan.steps
        if (index >= steps.size) {
            current.active = false
            finish(current.id, PlanStage.FINISHED, current.outcomes.toList())
            return
        }
        current.index = index
        current.stepStartedAt = now
        current.route = null
        val step = steps[index]
        journal.observeHome(vehicle.home)
        val reason = ownershipBlock(vehicle)
            ?: validator.validate(steps.subList(index, steps.size), vehicle, journal.state)?.let { renumber(it, index) }
        if (reason != null) {
            fail(current, StepOutcome.rejected(step, reason))
            return
        }
        if (step.command.name == FlightCommand.MARK_POINT) {
            val marked = mark(step, vehicle)
            if (marked.status != StepStatus.COMPLETED) return fail(current, marked)
            record(current, index, marked)
            startStep(current, index + 1, vehicle, now)
            return
        }
        if (step.command.isMotion) {
            vehicle.position?.takeIf { vehicle.fresh(LocalPositionNed.messageId) }?.let(journal::departing)
        }
        val legs = if (!step.command.isMemoryMotion) null else try {
            route(step.command, vehicle).also { current.route = it }.legs
        } catch (e: CommandFormatException) {
            fail(current, StepOutcome.rejected(step, e.message ?: ArduPilotExecutor.NO_ROUTE))
            return
        }
        current.outcomes[index] = StepOutcome(step, StepStatus.RUNNING)
        outcomes = current.outcomes.toList()
        apply(current, current.executor.start(step.command, vehicle, now, index + 1, legs), vehicle, now)
    }

    /** Where a flight-memory step flies from the vehicle's position now, as [MemoryRoutes.flyable] flies it. */
    private fun route(command: FlightCommand, vehicle: VehicleState): Route {
        val position = vehicle.position?.takeIf { vehicle.fresh(LocalPositionNed.messageId) }
            ?: throw CommandFormatException(ArduPilotExecutor.STALE)
        val altitude = vehicle.altitudeRelM?.takeIf { vehicle.fresh(GlobalPositionInt.messageId) }
        return MemoryRoutes.flyable(MemoryRoutes.resolve(command, journal.state, position), position, altitude)
    }

    /**
     * A step telemetry confirmed: the trail follows the vehicle (spec 2026-10-28 M1). A takeoff starts a new flight;
     * a move or a flight to a remembered position adds where it ended (and, as it started, where it started from when
     * that was not the newest position: [FlightJournal.departing]); go back and retrace forget what they undid.
     */
    private fun remember(current: PlanRun, command: FlightCommand, vehicle: VehicleState) {
        val position = vehicle.position?.takeIf { vehicle.fresh(LocalPositionNed.messageId) }
        val keep = current.route?.keep
        when {
            command.name == FlightCommand.TAKEOFF ->
                if (position != null) journal.takeoffCompleted(position) else journal.keepTrail(0)
            command.isMemoryMotion && keep != null -> journal.keepTrail(keep)
            command.isMotion && position != null -> journal.arrived(position, current.route?.label ?: command.description)
        }
    }

    private fun apply(current: PlanRun, update: StepUpdate, vehicle: VehicleState, now: Long) {
        val step = current.plan.steps[current.index]
        when (update) {
            StepUpdate.Running -> {}
            is StepUpdate.Completed -> {
                remember(current, step.command, vehicle)
                record(current, current.index, StepOutcome.completed(step, update.detail))
                startStep(current, current.index + 1, vehicle, now)
            }
            is StepUpdate.Rejected -> fail(current, StepOutcome.rejected(step, update.reason))
            is StepUpdate.Unknown -> fail(current, StepOutcome.unknown(step, update.reason))
        }
    }

    private fun advance(current: PlanRun, vehicle: VehicleState, acks: List<CommandAck>, texts: List<StatusText>, now: Long) {
        val text = texts.firstOrNull(VehicleEvents::abortsPlan)
        when {
            text != null -> abort(current, "Autopilot reported \"${text.text}\"; vehicle outcome unknown.")
            current.executor.externalModeChange(vehicle) -> abort(current, EXTERNAL_CHANGE)
            now - maxOf(current.stepStartedAt, current.executor.legStartedAtMs) > timing.stepDeadlineMs ->
                abort(current, ArduPilotExecutor.NO_CONFIRMATION)
            else -> apply(current, current.executor.update(vehicle, acks, now), vehicle, now)
        }
    }

    /** Ends the plan at its current step: UNKNOWN if anything of it was sent, else REJECTED. */
    private fun abort(current: PlanRun, reason: String) {
        val step = current.plan.steps[current.index]
        trace.mark(TracePoint.PLAN_ABORT, current.id, mapOf("step" to current.index + 1, "reason" to reason))
        fail(current, if (current.executor.sentInStep) StepOutcome.unknown(step, reason) else StepOutcome.rejected(step, reason))
    }

    private fun fail(current: PlanRun, outcome: StepOutcome) {
        current.executor.cancel()
        current.active = false
        record(current, current.index, outcome)
        for (i in current.index + 1 until current.outcomes.size) record(current, i, StepOutcome.skipped(current.plan.steps[i]))
        finish(current.id, PlanStage.FAILED, current.outcomes.toList())
    }

    private fun record(current: PlanRun, index: Int, outcome: StepOutcome) {
        current.outcomes[index] = outcome
        traceOutcome(current.id, index, outcome)
    }

    private fun traceOutcome(id: Long, index: Int, outcome: StepOutcome) = trace.mark(TracePoint.STEP_OUTCOME, id,
        mapOf("step" to index + 1, "status" to outcome.status, "message" to outcome.message))

    /** A step may start only while the app owns the mode of an armed vehicle (spec 4.11 "External mode"). */
    private fun ownershipBlock(vehicle: VehicleState): String? =
        if (vehicle.connected && vehicle.armed == true && !watch.ownsMode(vehicle)) StopPolicy.externalBanner(vehicle) else null

    // --- STOP ---

    private fun doStop(trigger: StopTrigger, heldOneSecond: Boolean, atNanos: Long) {
        val id = ++nextId
        trace.mark(TracePoint.STOP_TRIGGER, id, mapOf("trigger" to trigger, "held" to heldOneSecond), atNanos)
        invalidate(null)
        // Read before the plan is cancelled: its mode change or ARM in flight is still the app's (ModeWatch).
        val vehicle = store.snapshot()
        val owns = watch.ownsMode(vehicle)
        val armed = watch.armed(vehicle)
        val modesInPlay = watch.modesInPlay(vehicle)
        activeRun()?.let { abort(it, INTERRUPTED_BY_STOP) }
        val previous = stopLane
        if (previous?.sending == true) return // a STOP is already being sent and re-sent
        val live = link
        val decision = StopPolicy.decide(if (live == null) null else vehicle, trigger, heldOneSecond, owns, execution, armed)
        if (decision is StopDecision.NothingSent && previous != null && !previous.settled) {
            // The last STOP was ACKed and its heartbeat check is running: let it finish.
            trace.mark(TracePoint.STOP_NOTHING_SENT, id, mapOf("reason" to decision.message, "previous" to "being checked"))
            return
        }
        previous?.abandon() // a new decision replaces the last STOP's heartbeat check
        stopLane = null
        stopTrigger = trigger
        stopId = id
        when (decision) {
            is StopDecision.NothingSent -> {
                stopOverride = false
                // Sending nothing does not silence an unconfirmed STOP's alarm.
                stopStatus = StopStatus(id, trigger, StopPhase.NOTHING_SENT, null, decision.message,
                    alarm = stopStatus?.alarm == true, override = false)
                trace.mark(TracePoint.STOP_NOTHING_SENT, id, mapOf("reason" to decision.message))
                updateHold(null)
            }
            is StopDecision.Send -> {
                stopOverride = decision.override
                if (decision.override) {
                    trace.mark(TracePoint.STOP_OVERRIDE, id, mapOf("trigger" to trigger,
                        "mode" to vehicle.mode?.let(CopterMode::name), "owner" to vehicle.modeOwnerLabel))
                }
                events.drainAcks() // anything already here answers an earlier command, never this STOP
                val lane = StopLane(live!!, store, timing, trace, id, decision.action, watch)
                stopLane = lane
                onStopProgress(lane, lane.start(clock(), vehicle, modesInPlay))
                ensureTicking()
            }
        }
    }

    private fun onStopProgress(lane: StopLane, progress: StopProgress) {
        val previous = lane.actions.getOrNull(lane.actions.size - 2)
        stopStatus = when (progress) {
            is StopProgress.Sending -> StopStatus(stopId, stopTrigger, StopPhase.SENDING, progress.action,
                StopPolicy.sendingMessage(progress.action, previous), lane.alarm, stopOverride)
            is StopProgress.Confirmed -> StopStatus(stopId, stopTrigger, StopPhase.CONFIRMED, progress.action,
                StopPolicy.confirmedMessage(progress.action), lane.alarm, stopOverride)
            is StopProgress.TakenOver -> StopStatus(stopId, stopTrigger, StopPhase.NOT_CONFIRMED, progress.action,
                StopPolicy.takenOverMessage(progress.mode), alarm = true, override = stopOverride)
            is StopProgress.NotConfirmed -> StopStatus(stopId, stopTrigger, StopPhase.NOT_CONFIRMED, progress.action,
                StopPolicy.NOT_CONFIRMED, alarm = true, override = stopOverride)
        }.let { if (it.alarm && Triple(it.id, it.phase, it.action) == acknowledged) it.copy(alarm = false) else it }
        if (lane.settled) stopLane = null
        updateHold(lane)
    }

    /**
     * A HOLD submitted with no plan running shows as a one-step plan that follows its STOP until the STOP settles:
     * COMPLETED on the BRAKE ACK (spec 4.14), UNKNOWN if no heartbeat then shows BRAKE.
     */
    private fun updateHold(lane: StopLane?) {
        val step = holdStep ?: return
        val stop = stopStatus ?: return
        val outcome = holdOutcome(step, stop)
        if (stop.phase == StopPhase.SENDING) {
            outcomes = listOf(outcome)
            return
        }
        if (stage == PlanStage.EXECUTING || outcomes != listOf(outcome)) {
            traceOutcome(stop.id, 0, outcome)
            finish(stop.id, if (outcome.status == StepStatus.COMPLETED) PlanStage.FINISHED else PlanStage.FAILED, listOf(outcome))
        }
        if (lane?.settled != false) holdStep = null
    }

    private fun holdOutcome(step: PlanStep, stop: StopStatus): StepOutcome = when (stop.phase) {
        StopPhase.NOTHING_SENT -> StepOutcome.rejected(step, stop.message)
        StopPhase.SENDING -> StepOutcome(step, StepStatus.RUNNING, stop.message)
        StopPhase.CONFIRMED ->
            if (stop.action == StopAction.BRAKE && !stop.alarm) StepOutcome.completedByAck(step)
            else StepOutcome.unknown(step, "${stop.message}. Check the simulator before sending another command.")
        StopPhase.NOT_CONFIRMED -> StepOutcome.unknown(step, stop.message)
    }

    // --- the actor's turn: every store update (coalesced) and every tick while busy ---

    private fun turn() {
        val now = clock()
        // Drained first: the snapshot is then at least as new as every ACK and text, and the frames before them.
        val acks = events.drainAcks()
        val texts = events.drainTexts()
        val vehicle = store.snapshot()
        journal.observeHome(vehicle.home)
        traceOwner(vehicle)
        stopLane?.let { onStopProgress(it, it.update(acks, vehicle, now)) }
        activeRun()?.let { advance(it, vehicle, acks, texts, now) }
        pending?.let { if (readiness(vehicle) != it.readiness) invalidate(READINESS_CHANGED) }
        if (activeRun() == null && stopLane == null) {
            ticker?.cancel(false)
            ticker = null
        }
    }

    private fun ensureTicking() {
        if (ticker != null) return
        ticker = actor.scheduleAtFixedRate({
            turn()
            settle()
        }, tickMs, tickMs, TimeUnit.MILLISECONDS)
    }

    /** S11: every mode-owner change is traced. */
    private fun traceOwner(vehicle: VehicleState) {
        if (!vehicle.connected) return
        val owned = watch.ownsMode(vehicle)
        if (owned == lastOwned) return
        lastOwned = owned
        trace.mark(TracePoint.MODE_OWNER, fields = mapOf(
            "owner" to if (owned) ModeOwner.APP else ModeOwner.EXTERNAL,
            "label" to vehicle.modeOwnerLabel, "mode" to vehicle.mode?.let(CopterMode::name),
        ))
    }

    // --- pending plan ---

    private fun readiness(vehicle: VehicleState) = Readiness(
        connected = vehicle.connected,
        armed = vehicle.armed,
        landed = vehicle.landedState.takeIf { vehicle.fresh(ExtendedSysState.messageId) },
        owned = watch.ownsMode(vehicle),
        positionReady = vehicle.positionReady,
    )

    private fun expire(plan: PendingPlan) {
        if (pending?.plan === plan) invalidate(EXPIRED)
        settle()
    }

    /** Drops the pending plan, if any; [reason] is shown as the notice. */
    private fun invalidate(reason: String?) {
        if (pending == null) return
        clearPending()
        dropShownPlan(reason)
    }

    private fun clearPending() {
        pending?.expiry?.cancel(false)
        pending = null
    }

    private fun dropShownPlan(reason: String?) {
        if (stage == PlanStage.AWAITING_CONFIRMATION) show(null, null, PlanStage.IDLE, emptyList())
        notice = reason
    }

    private fun detachNow() {
        invalidate(DISCONNECTED_NOTICE)
        activeRun()?.let { abort(it, DISCONNECTED) }
        stopLane?.let { lane ->
            // A STOP still re-sending is not confirmed; one already ACKed stays confirmed (its check just ends).
            if (lane.sending) {
                lane.cancel()
                onStopProgress(lane, StopProgress.NotConfirmed(lane.actions.last()))
            }
        }
        stopLane = null
        holdStep = null
        updateStreaming() // off while the link can still send it
        link = null
        execution = false
        streaming = false
        watch.reset()
        lastOwned = null
    }

    // --- view ---

    /** Shows another plan: a HOLD row stops following its STOP. */
    private fun show(shown: FlightPlan?, from: PlanSource?, newStage: PlanStage, newOutcomes: List<StepOutcome>) {
        plan = shown
        source = from
        stage = newStage
        outcomes = newOutcomes
        result = null
        holdStep = null
    }

    /** The shown plan (by default the current one) has ended; [id] is its trace id. */
    private fun finish(
        id: Long,
        newStage: PlanStage,
        final: List<StepOutcome>,
        shown: FlightPlan? = plan,
        from: PlanSource? = source,
    ) {
        plan = shown
        source = from
        stage = newStage
        outcomes = final
        result = summary(final)
        trace.mark(TracePoint.PLAN_END, id, mapOf("stage" to newStage, "result" to result))
        val wire = from?.name?.lowercase() ?: "unknown"
        journal.recordPlan(final.map {
            FlightJournal.RecordedStep(it.step.command.name, it.step.command.arguments, it.step.command.description,
                wire, it.status.name)
        }, key = id)
    }

    private fun publish() {
        val current = activeRun()
        val next = SupervisorState(
            linked = link != null,
            execution = execution,
            stage = stage,
            pending = pending?.plan,
            plan = plan,
            source = source,
            outcomes = current?.outcomes?.toList() ?: outcomes,
            result = result,
            stop = stopStatus,
            notice = notice,
        )
        if (next == state) return
        state = next
        for (listener in listeners) {
            try {
                listener(next)
            } catch (_: Exception) {
                // A listener must not break the supervisor.
            }
        }
    }

    private class Pending(val plan: PendingPlan, val readiness: Readiness, val expiry: ScheduledFuture<*>)

    private data class Readiness(
        val connected: Boolean,
        val armed: Boolean?,
        val landed: Int?,
        val owned: Boolean,
        val positionReady: Boolean,
    )

    private class PlanRun(val id: Long, val plan: FlightPlan, val executor: ArduPilotExecutor) {
        val outcomes = plan.steps.map { StepOutcome(it, StepStatus.PENDING) }.toMutableList()
        var index = 0
        var stepStartedAt = 0L
        var active = true
        /** The current step's flight-memory route, resolved when it started. */
        var route: Route? = null

        /** The current step or a later one is a move or a flight-memory flight. */
        fun movesLeft() = plan.steps.subList(index, plan.steps.size).any { it.command.isMotion }
    }

    private fun FlightPlan.hasMove() = steps.any { it.command.isMotion }

    companion object {
        const val PENDING_TTL_MS = 15_000L
        const val TICK_MS = 50L

        const val NO_PLAN = "No plan is waiting for confirmation."
        const val TOKEN_MISMATCH = "This confirmation is for a different plan."
        const val EXPIRED = "The confirmation expired. Say the command again."
        const val BUSY = "A plan is running. Say stop first."
        const val STOP_IN_PROGRESS = "STOP is still in progress. Wait for it to finish."
        const val CANCELLED = "Plan cancelled."
        /** Spec A3: the AI source is the in-process pipeline's alone. */
        const val AI_ONLY_IN_PROCESS = "AI plans come only from the phone's own AI planner."
        /** Spec A1: HOLD and status run at once only when the rules read them. */
        const val AI_NO_IMMEDIATE = "The AI never runs hold or status: say \"hold\" or \"status\", or press STOP."
        /** Spec 2026-10-28 M5: flights to remembered positions come only from the rules or the tap pad. */
        const val AI_NO_MEMORY = "The AI never uses the flight memory: say \"return home\", \"go back\" or " +
            "\"retrace\" yourself, or use the flight memory card."
        const val MARK_NEEDS_HOME = "Home position is unknown. Wait for GPS home before saving a point."
        const val READINESS_CHANGED = "The vehicle state changed. Say the command again."
        const val EXECUTION_CHANGED = "Execution mode changed. Say the command again."
        const val LINK_LOST = "Link lost. Say the command again."
        const val DISCONNECTED_NOTICE = "Disconnected."
        const val INTERRUPTED_BY_STOP = "interrupted by STOP; check the simulator before sending another command."
        const val EXTERNAL_CHANGE = "Flight mode changed outside this app; vehicle outcome unknown."
        const val DISCONNECTED = "Disconnected while the plan was running; vehicle outcome unknown."

        private val stepNumber = Regex("^Step (\\d+) ")

        /** Validator messages number the remaining steps; show the plan's own step number. */
        private fun renumber(message: String, offset: Int) =
            stepNumber.replace(message) { "Step ${it.groupValues[1].toInt() + offset} " }

        /** Metres with one decimal, e.g. "12.3". */
        private fun metres(value: Double) = String.format(Locale.ROOT, "%.1f", value)

        /** v0.2.0: one step shows its message; several show a numbered list. */
        fun summary(outcomes: List<StepOutcome>): String? = when (outcomes.size) {
            0 -> null
            1 -> outcomes.single().message
            else -> outcomes.withIndex().joinToString("\n") { (i, o) -> "${i + 1}. ${o.step.command.description} — ${o.message}" }
        }
    }
}
