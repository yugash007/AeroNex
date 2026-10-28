package com.aeronex.app.session

import com.aeronex.app.command.Normalizer
import com.aeronex.app.command.ParseResult
import com.aeronex.app.command.PlanParser
import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.LinkListener
import com.aeronex.app.link.LinkSession
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.UdpTransport
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.memory.FlightJournal
import com.aeronex.app.observability.Trace
import com.aeronex.app.safety.PlanSource
import com.aeronex.app.safety.PlanStage
import com.aeronex.app.safety.PlanValidator
import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.safety.StopPhase
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.safety.SupervisorState
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.VehicleStateStore
import com.aeronex.app.vehicle.executor.ExecutorTiming
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier

/**
 * The flight session without Android (the service and the `aeronex/session` channel are thin wrappers around it).
 * For the process it owns one [VehicleStateStore], one [SafetySupervisor] and one [Trace]; per [connect] a new
 * [com.aeronex.app.link.Transport] (from [transports]) and [LinkSession], since both are single-use. The
 * store is reset on every connect and disconnect, link frames go to [VehicleStateStore.accept], and link state to the
 * supervisor. Dry run is the default of every connection (S6).
 *
 * Speech (T12, optional [voice]): a [VoiceSession] fed by the microphone while [startVoice] is in effect (the service
 * starts it with the microphone foreground type). [pttDown] / [pttUp] come from the PTT bar and Volume-Up; every STOP
 * trigger ends PTT and discards the utterance (spec 4.4), and a plan from an utterance pressed before a STOP is never
 * shown ([stops]). Recognised plans are submitted with the Phone source, exactly like [submitText].
 *
 * AI (0.4.0, optional [ai], spec "Command path" and A1-A6): typed text or a transcript the rules do not understand (and
 * only that) goes to the [AiRouter]: gloss, LFM planner and Needle on the AI thread, grounding, then a plan with the AI
 * source through [SafetySupervisor.submitAi] (validated, one CONFIRM). [submitText] then answers `{thinking, request}`
 * at once and the outcome arrives in the snapshot's `ai` section. STOP, Cancel, a new press, new text or a plan, and a
 * disconnect cancel the thinking, and a plan from a cancelled request or one older than the latest STOP is never
 * shown. The AI never waits on, or holds up, the link, supervisor, STOP or speech threads.
 *
 * Flight memory (spec 2026-10-28): the supervisor's [journal] (saved by its store, so it survives a restart) is the
 * snapshot's `memory` section; [runAgain] shows a recorded plan again and [clearMemory] forgets the memory.
 *
 * Thread use: [connect], [disconnect], [setExecution], [submitPlan], [submitText], [runAgain] and [confirm] block on the supervisor (up to a
 * few seconds while a link stops), so call them off the main thread. [stop], [cancel], [cancelAi], [acknowledgeAlarm],
 * [invalidatePending], [pttDown] and [pttUp] only post or queue and may be called from any thread, the main thread
 * included. [snapshot] and [active] are safe anywhere.
 */
class SessionController(
    private val transports: (SessionConfig) -> TransportLease = SessionConfig::plainTransport,
    traceClock: LongSupplier = LongSupplier(System::nanoTime),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Runs once on each link thread before its first I/O, e.g. to raise its priority. */
    private val linkThreadSetup: () -> Unit = {},
    private val timingFor: (TelemetryProfile) -> ExecutorTiming = ExecutorTiming::forProfile,
    validator: PlanValidator = PlanValidator(),
    voice: VoiceConfig? = null,
    ai: AiConfig? = null,
    /** The flight memory; [FlightJournal]'s store decides whether it outlives the process. */
    val journal: FlightJournal = FlightJournal(),
) : AutoCloseable, VoiceSink {
    private class Connection(val config: SessionConfig, val lease: TransportLease, val link: LinkSession)

    val trace = Trace(traceClock)
    private val latency = LatencyBook().also { trace.addSink(it) }
    private val store = VehicleStateStore(clock)
    private val supervisor = SafetySupervisor(store, validator, trace, clock, journal = journal)
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val seq = AtomicLong()
    private val snapshotLock = Any()
    @Volatile private var connection: Connection? = null
    /** Orders STOPs against voice submissions (see [stops]). */
    private val stopOrder = Any()
    @Volatile private var stopCount = 0L
    @Volatile private var selectedLanguage = voice?.language ?: VoiceConfig.DEFAULT_LANGUAGE

    /** The AI path; null where it is not configured (host tests without it). */
    private val aiRouter: AiRouter? = ai?.let { AiRouter(it, trace, clock, ::notifyChanged, ::deliverAi) }

    /** The Models screen's AI rows; null without AI models. */
    private val aiModelSetup: AiModelSetup? = ai?.models?.let { store ->
        // The cancel is ordered against a racing AI delivery; the native unload runs outside the lock, so a STOP never
        // waits for it (audit S8).
        AiModelSetup(store, ai.importRoot, beforeRemove = {
            synchronized(stopOrder) { aiRouter?.cancel("model removed") }
            aiRouter?.release()
        },
            changed = { aiRouter?.modelsChanged(); notifyChanged() })
    }

    /** Speech in the service; null where voice is not configured (host tests of the link and supervisor). */
    val voice: VoiceSession? = voice?.let {
        VoiceSession(it.capture, it.engines, this, trace, it.language, it.speechThreadSetup, it.saveLanguage)
    }

    /** Called on [pttDown] while voice is off: the service adds the microphone type and calls [startVoice]. */
    @Volatile var microphoneNeeded: () -> Unit = {}

    /** The Flight screen is in front (Volume-Up is push-to-talk only there, spec 4.4). */
    @Volatile var flightScreenVisible = false

    /** Set by the foreground service while it runs (reported as `link.foregroundService`). */
    @Volatile var foregroundService = false
        set(value) {
            field = value
            notifyChanged()
        }

    init {
        supervisor.addListener { notifyChanged() }
    }

    /** A session is open, or a plan or STOP is still in progress: Volume-Down is STOP. */
    val active: Boolean
        get() {
            val state = supervisor.state
            return connection != null || state.stage == PlanStage.EXECUTING || state.stop?.phase == StopPhase.SENDING
        }

    /** Called on any thread after the supervisor state, the link state or the connection changed; must be quick. */
    fun addChangeListener(listener: () -> Unit) {
        changeListeners += listener
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners -= listener
    }

    /** Opens the link; the snapshot right after. [SessionException] when already connected or it cannot open. */
    @Synchronized fun connect(config: SessionConfig): Map<String, Any?> {
        if (connection != null) throw SessionException(SessionException.ALREADY_CONNECTED, ALREADY_CONNECTED)
        store.freshness = config.profile.freshnessMs()
        store.reset()
        val lease = transports(config)
        val link = LinkSession(ThreadSetupTransport(lease.transport, linkThreadSetup), MavlinkEncoder(), MavlinkParser(),
            config.profile, object : LinkListener {
                override fun onFrame(frame: MavlinkFrame) = store.accept(frame)
                override fun onLinkState(up: Boolean) {
                    supervisor.onLinkState(up)
                    notifyChanged()
                }
            })
        try {
            link.start()
            await(supervisor.attach(link, timingFor(config.profile)))
        } catch (e: Exception) {
            link.stop()
            lease.release()
            store.reset()
            throw SessionException(SessionException.CONNECT_FAILED, "Cannot open the ${config.label}: ${e.message ?: e.javaClass.simpleName}")
        }
        connection = Connection(config, lease, link)
        trace.mark(SESSION_CONNECT, fields = mapOf("link" to lease.transport.description, "profile" to config.profile.name))
        notifyChanged()
        return snapshot()
    }

    /**
     * Aborts a running plan (UNKNOWN), drops a pending one, ends execution, then stops the link and forgets the
     * vehicle. The link first gets up to [DISCONNECT_DRAIN_MS] to send what the supervisor queued on detach (the
     * echo stream's off request), so the vehicle does not keep streaming to a closed port. No-op when not connected.
     */
    @Synchronized fun disconnect() {
        cancelAi(AI_DISCONNECTED)
        stopVoice()
        val current = connection ?: return
        try {
            await(supervisor.detach())
        } catch (_: SessionException) {
            // The link closes anyway: the onboard GCS failsafe covers a lost link.
        }
        connection = null
        current.link.drain(DISCONNECT_DRAIN_MS)
        current.link.stop()
        current.lease.release()
        store.reset()
        trace.mark(SESSION_DISCONNECT)
        notifyChanged()
    }

    /** Live execution for this connection (false = dry run); ignored without a link. The resulting state. */
    fun setExecution(enabled: Boolean): Boolean {
        await(supervisor.setExecution(enabled))
        return supervisor.state.execution
    }

    /**
     * Validates and shows a plan, or runs HOLD and status at once; see [SessionSchema.submitted]. A malformed request
     * throws [SessionException]; every plan rejection is an `{error}` answer.
     */
    fun submitPlan(arguments: Any?): Map<String, Any?> {
        cancelAi(AI_NEW_INPUT)
        val request = try {
            SessionSchema.parsePlan(arguments)
        } catch (e: CommandFormatException) {
            trace.mark(PLAN_SUBMITTED, fields = mapOf("utterance" to (arguments as? Map<*, *>)?.get("utterance"),
                "rejected" to e.message))
            return linkedMapOf("error" to e.message)
        }
        trace.mark(PLAN_SUBMITTED, fields = mapOf("utterance" to request.utterance, "source" to request.source,
            "plan" to request.plan.toString()))
        return SessionSchema.submitted(await(supervisor.submit(request.plan, request.source)), clock())
    }

    /**
     * Typed or recognised [text] in the selected [language] ("te-IN", "te"), parsed here by the Kotlin parser
     * ([PlanParser.parseText]: the rules, or the ta/kn/ml templates), then shown or run exactly as [submitPlan]: the
     * same answers, STOP/HOLD and status at once, anything else waits for CONFIRM. A parser rejection is an `{error}`
     * answer, except "not understood", which the AI may take: `{thinking: true, request}` (see the class notes).
     */
    fun submitText(text: String, language: String, source: PlanSource = PlanSource.TYPED): Map<String, Any?> {
        cancelAi(AI_NEW_INPUT)
        return when (val parsed = PlanParser.parseText(text, language)) {
            is ParseResult.Rejected -> {
                trace.mark(PLAN_SUBMITTED, fields = mapOf("utterance" to text, "rejected" to parsed.message))
                when (val routed = routeToAi(text, parsed.message, stopCount)) {
                    is AiRouter.Routed.Thinking -> linkedMapOf("thinking" to true, "request" to routed.request)
                    is AiRouter.Routed.Unavailable -> linkedMapOf("error" to routed.message)
                    AiRouter.Routed.NotEligible -> linkedMapOf("error" to parsed.message)
                }
            }
            is ParseResult.Accepted -> {
                trace.mark(PLAN_SUBMITTED, fields = mapOf("utterance" to text, "source" to source,
                    "plan" to parsed.plan.toString()))
                SessionSchema.submitted(await(supervisor.submit(parsed.plan, source)), clock())
            }
        }
    }

    fun confirm(token: String): Map<String, Any?> = SessionSchema.confirmed(await(supervisor.confirm(token)))

    /**
     * Shows the recorded plan [planId] again (the flight memory's "run again"), validated against the vehicle and the
     * memory now, with the Tap source: an operator's tap, never the AI. The same answers as [submitPlan].
     */
    fun runAgain(planId: Long): Map<String, Any?> {
        cancelAi(AI_NEW_INPUT)
        val records = journal.plan(planId)
        if (records.isEmpty()) return linkedMapOf("error" to PLAN_GONE)
        val plan = try {
            FlightPlan(records.map {
                PlanStep(FlightCommand.fromToolCalls(listOf(mapOf("name" to it.name, "arguments" to it.arguments))),
                    StepSource.RULES, it.description)
            })
        } catch (e: CommandFormatException) {
            return linkedMapOf("error" to e.message)
        }
        trace.mark(PLAN_SUBMITTED, fields = mapOf("utterance" to null, "source" to PlanSource.TAP,
            "plan" to plan.toString(), "runAgain" to planId))
        return SessionSchema.submitted(await(supervisor.submit(plan, PlanSource.TAP)), clock())
    }

    /** Forgets the flown path, the spots and the recorded plans; home stays known. */
    fun clearMemory() {
        journal.clear()
        notifyChanged()
    }

    /** STOP while a plan runs (S5), else drops the pending plan; never disconnects. Ends PTT. Posts only. */
    fun cancel() = synchronized(stopOrder) {
        stopCount++
        voice?.cancelPtt("Cancel")
        supervisor.cancel()
        aiRouter?.cancel(AI_STOPPED)
        Unit
    }

    /**
     * Any STOP trigger; [heldOneSecond] only counts for the on-screen button (spec 4.12). Ends PTT and discards its
     * utterance (spec 4.4). Posts only.
     */
    fun stop(trigger: StopTrigger, heldOneSecond: Boolean = false) = synchronized(stopOrder) {
        stopCount++
        voice?.cancelPtt(trigger.name)
        supervisor.stop(trigger, heldOneSecond)
        aiRouter?.cancel(AI_STOPPED)
        Unit
    }

    /** The "Thinking…" Cancel button: the AI request in progress is dropped (a late answer is never shown). */
    fun cancelAi() = cancelAi(AI_CANCELLED)

    /**
     * Cancels the AI request in progress. One whose plan went to the supervisor already (the cancel lost the race with
     * its delivery) also drops that plan, with [dropReason], so it never stays pending after a cancel.
     */
    private fun cancelAi(reason: String, dropReason: String = SafetySupervisor.CANCELLED) {
        val router = aiRouter ?: return
        synchronized(stopOrder) {
            if (router.cancel(reason)?.delivered == true) supervisor.invalidatePending(dropReason)
        }
    }

    /** From onTrimMemory: the AI planner frees its model (now, or when its run ends). */
    fun trimMemory(@Suppress("UNUSED_PARAMETER") level: Int) {
        aiRouter?.trimMemory()
    }

    /** The Models screen's AI rows ([AiModelSetup.status]); without AI models, unavailable and empty. Blocking. */
    fun aiModels(): Map<String, Any?> = aiModelSetup?.status(aiStatus())
        ?: linkedMapOf("available" to false, "reason" to aiStatus().reason, "models" to emptyList<Any>(), "transfer" to null,
            "error" to null)

    fun downloadAiModel(id: String) = models().download(id)

    fun importAiModel(id: String) = models().import(id)

    fun cancelAiModel() {
        aiModelSetup?.cancel()
    }

    fun removeAiModel(id: String): Boolean = models().remove(id)

    private fun models() = aiModelSetup ?: throw SessionException(SessionException.BAD_ARGS, "AI models are not part of this build.")

    private fun aiStatus(): AiStatus = aiRouter?.status() ?: AiStatus.OFF

    /** A rejected utterance to the AI, if it may take it (see [AiRouter.route]). */
    private fun routeToAi(text: String, rulesMessage: String, stops: Long): AiRouter.Routed =
        aiRouter?.route(text, rulesMessage, stops) ?: AiRouter.Routed.NotEligible

    /** Shows an AI plan on the AI thread, unless its request was cancelled or a STOP came after it was said. */
    private fun deliverAi(request: AiRequest, plan: FlightPlan): AiDelivery {
        val submitted = synchronized(stopOrder) {
            if (request.cancelled || stopCount != request.stops) null
            else supervisor.submitAi(plan).also { request.delivered = true }
        } ?: return AiDelivery.Discarded
        trace.mark(PLAN_SUBMITTED, request.traceId, mapOf("utterance" to request.heard, "source" to PlanSource.AI,
            "plan" to plan.toString()))
        return try {
            (SessionSchema.submitted(await(submitted), clock())["error"] as String?)?.let { AiDelivery.Rejected(it) }
                ?: AiDelivery.Shown
        } catch (e: SessionException) {
            AiDelivery.Rejected(e.message)
        }
    }

    // --- voice (T12) ---

    /** The selected speech language ("te-IN", "te" or a name); the code. [SessionException] BAD_ARGS when unknown. */
    fun setLanguage(language: String): String {
        val code = Normalizer.languageCode(language)
            ?: throw SessionException(SessionException.BAD_ARGS, "Unknown language: $language")
        selectedLanguage = code
        voice?.setLanguage(code)
        notifyChanged()
        return code
    }

    /**
     * Push-to-talk pressed (the PTT bar or Volume-Up). New input drops a plan that was not confirmed (S2) but never
     * stops a running one. Only while connected; with voice off it asks for the microphone ([microphoneNeeded]).
     */
    fun pttDown() {
        cancelAi(AI_NEW_PRESS)
        if (connection == null) return
        // A cold AI planner (idle unload, onTrimMemory) loads while the operator speaks.
        aiRouter?.prewarm()
        val voice = voice ?: return
        // The new press is counted before the pending plan is dropped: a plan of an earlier utterance submitted after
        // this point is refused ([submit]), one submitted before is dropped by the invalidation (audit S4).
        synchronized(stopOrder) { voice.pttDown() }
        supervisor.invalidatePending(SafetySupervisor.CANCELLED)
        if (!voice.running) microphoneNeeded()
    }

    fun pttUp() {
        voice?.pttUp()
    }

    /** Opens the microphone and the speech thread (the service, once it may record). */
    fun startVoice() {
        voice?.start()
    }

    /** Closes them; [reason] is what the speech status says until the next start. */
    fun stopVoice(reason: VoiceReason = VoiceReason.NOT_STARTED) {
        val voice = voice ?: return
        voice.stop()
        voice.offReason = reason
    }

    override val stops: Long get() = stopCount

    override fun submit(plan: FlightPlan, transcript: String, traceId: Long, stopsAtPress: Long): Map<String, Any?> {
        val submitted = synchronized(stopOrder) {
            when {
                stopCount != stopsAtPress -> return linkedMapOf("error" to STOPPED_WHILE_SPEAKING)
                voice != null && voice.latestPress != traceId -> return linkedMapOf("error" to NEWER_PRESS)
                else -> supervisor.submit(plan, PlanSource.PHONE)
            }
        }
        trace.mark(PLAN_SUBMITTED, traceId, mapOf("utterance" to transcript, "source" to PlanSource.PHONE,
            "plan" to plan.toString()))
        return SessionSchema.submitted(await(submitted), clock())
    }

    override fun voiceStop(trigger: StopTrigger) = synchronized(stopOrder) {
        stopCount++
        supervisor.stop(trigger)
        aiRouter?.cancel(AI_STOPPED)
        Unit
    }

    /** A transcript the rules did not understand: the AI may take it (its answer arrives in the snapshot). */
    override fun notUnderstood(transcript: String, rulesMessage: String, stopsAtPress: Long): String? =
        when (val routed = routeToAi(transcript, rulesMessage, stopsAtPress)) {
            is AiRouter.Routed.Thinking -> null
            is AiRouter.Routed.Unavailable -> routed.message
            AiRouter.Routed.NotEligible -> rulesMessage
        }

    override fun spotterArmed(): Boolean = spotterArmed(store.snapshot())

    /** Link up, the vehicle disarmed and landed (a new voice pack may replace the voice engines only then). */
    override fun onTheGround(): Boolean {
        if (connection == null) return false
        val vehicle = store.snapshot()
        return vehicle.connected && vehicle.armed == false && vehicle.landedState == VehicleState.LANDED_ON_GROUND
    }

    override fun changed() = notifyChanged()

    fun acknowledgeAlarm() = supervisor.acknowledgeAlarm()

    /**
     * E.g. the app went to the background (S2): the pending confirmation no longer applies, and an AI request still
     * thinking is cancelled so it cannot show a plan afterwards.
     */
    fun invalidatePending(reason: String) {
        cancelAi(AI_INVALIDATED, reason)
        supervisor.invalidatePending(reason)
    }

    /** Asks for every telemetry rate and HOME_POSITION again; false when not connected or not queued. */
    fun requestTelemetry(): Boolean = connection?.link?.requestTelemetry() ?: false

    /**
     * The [SessionSchema] snapshot now. Built under its own lock, so a higher `seq` never carries older content when
     * the pump and the channel build snapshots at once (audit F13); never the controller's monitor, which [connect]
     * holds for seconds.
     */
    fun snapshot(): Map<String, Any?> = synchronized(snapshotLock) {
        val state: SupervisorState = supervisor.state
        val vehicle = store.snapshot()
        val speech = voice?.status()?.let { it.copy(spotterArmed = it.spotterArmed && spotterArmed(vehicle)) }
            ?: VoiceStatus.off(selectedLanguage, VoiceReason.NOT_STARTED)
        SessionSchema.snapshot(seq.incrementAndGet(), clock(), linkInfo(), state, vehicle, latency.summaries(), speech,
            aiStatus(), journal.state)
    }

    /** The link, the store and the supervisor as the snapshot reports them (also for the notification). */
    fun linkInfo(): LinkInfo {
        val current = connection ?: return LinkInfo.down(foregroundService)
        val stats = current.link.stats
        return LinkInfo(
            state = if (stats.up) LinkState.UP else LinkState.CONNECTING,
            transport = current.config.type.wireName,
            description = current.lease.transport.description,
            profile = current.config.profile.name,
            unsigned = !stats.signed,
            heartbeatAgeMs = stats.lastHeartbeatElapsedMs,
            txErrors = stats.txErrors,
            droppedDatagrams = (current.lease.transport as? UdpTransport)?.droppedDatagrams,
            foregroundService = foregroundService,
        )
    }

    fun vehicle() = store.snapshot()

    fun supervisorState(): SupervisorState = supervisor.state

    override fun close() {
        disconnect()
        aiModelSetup?.close()
        aiRouter?.close()
        supervisor.close()
    }

    /** Spec 4.12: the voice spotter listens only while the vehicle is armed or airborne and the app owns the mode. */
    private fun spotterArmed(vehicle: VehicleState): Boolean = connection != null && vehicle.connected &&
        vehicle.modeOwner == ModeOwner.APP && (vehicle.armed == true || vehicle.landedState in AIRBORNE)

    private fun notifyChanged() {
        for (listener in changeListeners) {
            try {
                listener()
            } catch (_: Exception) {
                // A listener must not break the session.
            }
        }
    }

    private fun <T> await(future: Future<T>): T = try {
        future.get(SUPERVISOR_TIMEOUT_S, TimeUnit.SECONDS)
    } catch (e: ExecutionException) {
        throw SessionException(INTERNAL, e.cause?.message ?: "The flight supervisor failed.")
    } catch (_: TimeoutException) {
        throw SessionException(INTERNAL, "The flight supervisor did not answer.")
    }

    companion object {
        const val PLAN_SUBMITTED = "plan_submitted"
        const val SESSION_CONNECT = "session_connect"
        const val SESSION_DISCONNECT = "session_disconnect"
        const val ALREADY_CONNECTED = "Already connected. Disconnect first."
        const val BACKGROUNDED = "The app went to the background. Say the command again."
        const val STOPPED_WHILE_SPEAKING = "STOP was pressed: that command was discarded."
        const val NEWER_PRESS = "A newer command replaced that one."
        const val PLAN_GONE = "That plan is no longer in the flight memory."
        private const val AI_NEW_INPUT = "new input"
        private const val AI_NEW_PRESS = "new press"
        private const val AI_STOPPED = "STOP"
        private const val AI_CANCELLED = "Cancel"
        private const val AI_DISCONNECTED = "disconnect"
        private const val AI_INVALIDATED = "pending invalidated"
        private val AIRBORNE = setOf(VehicleState.LANDED_IN_AIR, VehicleState.LANDED_TAKEOFF, VehicleState.LANDED_LANDING)
        const val INTERNAL = "INTERNAL"
        private const val SUPERVISOR_TIMEOUT_S = 5L
        /** At most this long on disconnect for the TX queue to empty (the echo stream's off request), then stop. */
        const val DISCONNECT_DRAIN_MS = 250L
    }
}
