package com.aeronex.app.session

import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.command.model.PlanStep
import com.aeronex.app.command.model.StepSource
import com.aeronex.app.link.mavlink.StatusText
import com.aeronex.app.observability.LatencySummary
import com.aeronex.app.safety.MiniJson
import com.aeronex.app.safety.PendingPlan
import com.aeronex.app.safety.PlanSource
import com.aeronex.app.safety.PlanStage
import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.safety.StepOutcome
import com.aeronex.app.safety.StepStatus
import com.aeronex.app.safety.StopPhase
import com.aeronex.app.safety.StopPolicy
import com.aeronex.app.safety.StopStatus
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.safety.SupervisorState
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.vehicle.CopterMode
import com.aeronex.app.vehicle.ExternalCause
import com.aeronex.app.vehicle.ModeOwner
import com.aeronex.app.vehicle.Ned
import com.aeronex.app.vehicle.Received
import com.aeronex.app.vehicle.VehicleState
import com.aeronex.app.vehicle.executor.StopAction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot contract with the Dart client (T8a parses these files): representative snapshots built through
 * [SessionSchema.snapshot] must equal app/test/fixtures/session/<name>.json byte for byte (line endings aside).
 * After a deliberate schema change, regenerate with AERONEX_UPDATE_FIXTURES=1 (or -Daeronex.updateFixtures=1 on the
 * test JVM), bump [SessionSchema.VERSION] if a field changed meaning, and update the Dart parser.
 */
class SessionSchemaFixtureTest {
    companion object {
        private val update = System.getenv("AERONEX_UPDATE_FIXTURES") == "1" || System.getProperty("aeronex.updateFixtures") == "1"
        private const val AT_MS = 120_000L
        val NAMES = listOf("idle_disconnected", "connected_landed", "pending_plan", "executing_move", "stop_not_confirmed",
            "ptt_listening", "voice_unavailable", "ai_plan", "ai_thinking")

        /** app/test/fixtures/session, found from the Gradle test working directory (app/android/app) upwards. */
        fun fixtureDir(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                for (app in listOf(dir, File(dir, "app"))) {
                    if (File(app, "test/golden/plan_vectors.json").isFile) return File(app, "test/fixtures/session")
                }
                dir = dir.parentFile
            }
            error("app/test not found above ${System.getProperty("user.dir")}")
        }
    }

    private fun check(name: String, snapshot: Map<String, Any?>) {
        val text = JsonText.write(snapshot)
        val file = File(fixtureDir(), "$name.json")
        if (update) {
            fixtureDir().mkdirs()
            file.writeText(text, Charsets.UTF_8)
            return
        }
        assertTrue("$file is missing: run the test with AERONEX_UPDATE_FIXTURES=1", file.isFile)
        assertEquals("$name.json no longer matches SessionSchema; if the change is deliberate regenerate it with " +
            "AERONEX_UPDATE_FIXTURES=1 and update the Dart client", file.readText(Charsets.UTF_8).replace("\r\n", "\n"), text)
    }

    private fun ages(vararg pairs: Pair<Int, Long>) = mapOf(*pairs)
    private val freshAges = ages(0 to 310L, 1 to 420L, 30 to 60L, 32 to 60L, 33 to 60L, 193 to 520L, 242 to 9_800L, 245 to 180L)
    private fun latency(vararg stages: Pair<String, LatencySummary>): Map<String, LatencySummary?> =
        LatencyBook.Stage.entries.associate { it.key to null } + stages
    private fun move(direction: String, m: Number, clause: String = "") = PlanStep(
        FlightCommand.of(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to m)),
        StepSource.RULES, clause)
    private val land = PlanStep(FlightCommand.of(FlightCommand.LAND), StepSource.RULES, "land")
    private val wifiUp = LinkInfo(LinkState.UP, "udp", "UDP 192.168.0.5:14550 local *:14551", "wifi", unsigned = true,
        heartbeatAgeMs = 310, txErrors = 0, droppedDatagrams = 0, foregroundService = true)

    /** Voice ready on the resident Vosk model (en), as after a connect with the microphone allowed. */
    private val voiceReady = VoiceStatus("en", VoiceState.READY, VoiceLevel.OK, null, EngineKind.VOSK_GRAMMAR,
        SpotterKind.VOSK_STOP_GRAMMAR, nativeVoiceStop = true, partialStop = true)

    private val hovering = VehicleState(
        atMs = AT_MS, mode = CopterMode.GUIDED, armed = true, landedState = VehicleState.LANDED_IN_AIR,
        modeOwner = ModeOwner.APP, altitudeRelM = 3.02, position = Ned(1.5, 0.4, -3.02), velocity = Ned(0.0, 0.0, 0.0),
        yaw = 0.0, batteryPercent = 87, healthy = true, ekfFlags = 0x37F, home = Ned(0.0, 0.0, 0.0), ageMs = freshAges,
    )

    @Test fun idleDisconnected() = check("idle_disconnected", SessionSchema.snapshot(
        seq = 1, atMs = AT_MS, link = LinkInfo.down(foregroundService = false), supervisor = SupervisorState(),
        vehicle = VehicleState(atMs = AT_MS), latency = latency(), speech = VoiceStatus.off("te", VoiceReason.NOT_STARTED),
    ))

    @Test fun connectedLanded() = check("connected_landed", SessionSchema.snapshot(
        seq = 42, atMs = AT_MS, link = wifiUp, supervisor = SupervisorState(linked = true),
        vehicle = VehicleState(
            atMs = AT_MS, mode = CopterMode.STABILIZE, armed = false, landedState = VehicleState.LANDED_ON_GROUND,
            modeOwner = ModeOwner.EXTERNAL, externalCause = ExternalCause.PILOT_MODE, altitudeRelM = 0.02,
            position = Ned(0.1, -0.2, -0.02), velocity = Ned(0.0, 0.0, 0.0), yaw = 1.57, batteryPercent = 100,
            healthy = true, ekfFlags = 0x37F, home = Ned(0.0, 0.0, 0.0),
            lastStatusText = Received(StatusText(6, "EKF3 IMU0 is using GPS"), AT_MS - 5_000),
            ageMs = freshAges + (253 to 5_000L),
        ),
        latency = latency(),
        speech = voiceReady,
    ))

    @Test fun pendingPlan() {
        val plan = FlightPlan(listOf(move("forward", 2, "forward 2"), move("right", 2, "right 2")))
        val pending = PendingPlan("0b6f4d2a-6c4e-4c1e-9d0a-5e2f1c3b7a90", plan, PlanSource.TYPED, AT_MS + 12_400)
        check("pending_plan", SessionSchema.snapshot(
            seq = 97, atMs = AT_MS, link = wifiUp,
            supervisor = SupervisorState(linked = true, execution = true, stage = PlanStage.AWAITING_CONFIRMATION,
                pending = pending, plan = plan, source = PlanSource.TYPED,
                outcomes = plan.steps.map { StepOutcome(it, StepStatus.PENDING) }),
            vehicle = hovering,
            latency = latency("confirmToTx" to LatencySummary(1, 0.21, 0.21, 0.21),
                "confirmToAck" to LatencySummary(1, 4.25, 4.25, 4.25),
                "confirmToInAir" to LatencySummary(1, 2_840.5, 2_840.5, 2_840.5)),
            speech = voiceReady.copy(spotterArmed = true, utterance = 3, heard = "take off to 3 metres"),
        ))
    }

    @Test fun executingMove() {
        val plan = FlightPlan(listOf(move("forward", 2, "forward 2"), land))
        check("executing_move", SessionSchema.snapshot(
            seq = 131, atMs = AT_MS, link = wifiUp,
            supervisor = SupervisorState(linked = true, execution = true, stage = PlanStage.EXECUTING, plan = plan,
                source = PlanSource.PHONE,
                outcomes = listOf(StepOutcome(plan.steps[0], StepStatus.RUNNING), StepOutcome(plan.steps[1], StepStatus.PENDING))),
            vehicle = hovering.copy(position = Ned(2.6, 0.4, -3.01), velocity = Ned(1.8, 0.05, 0.0), altitudeRelM = 3.01,
                ageMs = freshAges + (85 to 140L)),
            latency = latency("confirmToMoveTx" to LatencySummary(2, 3.1, 4.2, 4.2),
                "moveTxToEcho" to LatencySummary(4, 121.5, 233.0, 233.0),
                "pttUpToFinal" to LatencySummary(4, 92.0, 141.5, 141.5),
                "finalToPlan" to LatencySummary(4, 1.8, 3.2, 3.2),
                "confirmToFrame" to LatencySummary(2, 3.1, 4.2, 4.2)),
            speech = voiceReady.copy(spotterArmed = true, utterance = 4, heard = "move forward 2 metres then land"),
        ))
    }

    @Test fun stopNotConfirmed() {
        val plan = FlightPlan(listOf(move("forward", 8, "forward 8")))
        val outcomes = listOf(StepOutcome.unknown(plan.steps[0], SafetySupervisor.INTERRUPTED_BY_STOP))
        check("stop_not_confirmed", SessionSchema.snapshot(
            seq = 260, atMs = AT_MS, link = wifiUp.copy(heartbeatAgeMs = 640, txErrors = 2),
            supervisor = SupervisorState(linked = true, execution = true, stage = PlanStage.FAILED, plan = plan,
                source = PlanSource.PHONE, outcomes = outcomes, result = SafetySupervisor.summary(outcomes),
                stop = StopStatus(57, StopTrigger.BUTTON, StopPhase.NOT_CONFIRMED, StopAction.BRAKE,
                    StopPolicy.takenOverMessage(CopterMode.LAND), alarm = true, override = false)),
            vehicle = hovering.copy(mode = CopterMode.LAND, landedState = VehicleState.LANDED_LANDING,
                modeOwner = ModeOwner.EXTERNAL, externalCause = ExternalCause.AUTOPILOT_FAILSAFE, altitudeRelM = 2.4,
                position = Ned(4.1, 0.4, -2.4), velocity = Ned(0.0, 0.0, 0.5), batteryPercent = 12,
                lastStatusText = Received(StatusText(2, "Battery failsafe"), AT_MS - 1_200),
                ageMs = freshAges + (253 to 1_200L)),
            latency = latency("stopToTx" to LatencySummary(3, 0.41, 0.88, 0.88),
                "stopToAck" to LatencySummary(3, 4.9, 7.7, 7.7)),
            speech = voiceReady.copy(utterance = 6, heard = "move forward 8 metres"),
        ))
    }

    @Test fun pttListening() = check("ptt_listening", SessionSchema.snapshot(
        seq = 305, atMs = AT_MS, link = wifiUp, supervisor = SupervisorState(linked = true, execution = true),
        vehicle = hovering, latency = latency(),
        speech = voiceReady.copy(state = VoiceState.LISTENING, spotterArmed = true, pttHeld = true, utterance = 7,
            partial = "move forward"),
    ))

    @Test fun voiceUnavailable() = check("voice_unavailable", SessionSchema.snapshot(
        seq = 44, atMs = AT_MS, link = wifiUp, supervisor = SupervisorState(linked = true),
        vehicle = VehicleState(atMs = AT_MS, mode = CopterMode.GUIDED, armed = false,
            landedState = VehicleState.LANDED_ON_GROUND, modeOwner = ModeOwner.EXTERNAL,
            externalCause = ExternalCause.PILOT_MODE, altitudeRelM = 0.0, position = Ned(0.0, 0.0, 0.0),
            velocity = Ned(0.0, 0.0, 0.0), yaw = 0.0, batteryPercent = 96, healthy = true, ekfFlags = 0x37F,
            home = Ned(0.0, 0.0, 0.0), ageMs = freshAges),
        latency = latency(),
        speech = VoiceStatus("ta", VoiceState.READY, VoiceLevel.NO_VOICE, VoiceReason.NEEDS_NEWER_PHONE, utterance = 1,
            error = VoiceReason.NEEDS_NEWER_PHONE.message),
    ))

    /** 0.4.0: a plan of the AI path waiting for CONFIRM, with what was heard and understood. */
    @Test fun aiPlan() {
        val ai = { step: PlanStep -> PlanStep(step.command, StepSource.AI, step.clause) }
        val plan = FlightPlan(listOf(ai(move("forward", 3, "move forward 3 meters")), ai(land)))
        val pending = PendingPlan("7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f", plan, PlanSource.AI, AT_MS + 13_100)
        check("ai_plan", SessionSchema.snapshot(
            seq = 412, atMs = AT_MS, link = wifiUp,
            supervisor = SupervisorState(linked = true, execution = true, stage = PlanStage.AWAITING_CONFIRMATION,
                pending = pending, plan = plan, source = PlanSource.AI,
                outcomes = plan.steps.map { StepOutcome(it, StepStatus.PENDING) }),
            vehicle = hovering,
            latency = latency("aiGloss" to LatencySummary(1, 0.8, 0.8, 0.8), "aiPlan" to LatencySummary(1, 1_840.0, 1_840.0, 1_840.0),
                "aiTools" to LatencySummary(1, 2.5, 2.5, 2.5), "aiTotal" to LatencySummary(1, 1_861.0, 1_861.0, 1_861.0)),
            speech = voiceReady.copy(language = "te", spotterArmed = true, utterance = 8,
                heard = "ముందుకు మూడు మీటర్లు తర్వాత దిగు"),
            ai = AiStatus(true, null, AiState.IDLE, 2, heard = "ముందుకు మూడు మీటర్లు తర్వాత దిగు",
                gloss = "forward 3 meters then land", understoodAs = "move forward 3 meters, then land", error = null),
        ))
    }

    /** 0.4.0: the AI thinking about typed text the rules did not understand. */
    @Test fun aiThinking() = check("ai_thinking", SessionSchema.snapshot(
        seq = 420, atMs = AT_MS, link = wifiUp, supervisor = SupervisorState(linked = true, execution = true),
        vehicle = hovering, latency = latency(), speech = voiceReady.copy(spotterArmed = true, utterance = 8),
        ai = AiStatus(true, null, AiState.THINKING, 3, heard = "go up a bit higher, by two and a half metres", gloss = null,
            understoodAs = null, error = null),
    ))

    @Test fun theFixtureDirectoryHoldsExactlyTheseValidJsonSnapshots() {
        if (update) return
        val files = fixtureDir().listFiles { f -> f.name.endsWith(".json") }!!.map { it.name.removeSuffix(".json") }.sorted()
        assertEquals(NAMES.sorted(), files)
        for (name in NAMES) {
            @Suppress("UNCHECKED_CAST")
            val parsed = MiniJson.parse(File(fixtureDir(), "$name.json").readText(Charsets.UTF_8)) as Map<String, Any?>
            assertEquals(SessionSchema.VERSION.toLong(), parsed["schemaVersion"])
            assertEquals(SessionSchema.TOP_LEVEL_KEYS, parsed.keys.toList())
        }
    }
}
