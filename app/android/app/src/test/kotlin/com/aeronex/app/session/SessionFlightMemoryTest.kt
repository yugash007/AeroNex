package com.aeronex.app.session

import com.aeronex.app.safety.SafetySupervisor
import com.aeronex.app.memory.FileJournalStore
import com.aeronex.app.memory.FlightJournal
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 2026-10-28 through the session: the `memory` snapshot section, `runAgain`, `clearMemory`, persistence. */
@Suppress("UNCHECKED_CAST")
class SessionFlightMemoryTest {
    private val vehicle = FakeArduCopter()
    private val dir: File = Files.createTempDirectory("aeronex-memory").toFile()
    private val file = File(dir, "flight_memory.json")
    /** Saves on the calling thread, so the file is current when a test reads it. */
    private fun journal() = FlightJournal(FileJournalStore(file) { it.run() })
    private var controller = newController(journal())
    private val api get() = SessionApi(controller)
    private val config = SessionConfig(LinkType.UDP, "127.0.0.1", 14550, 0)

    private fun newController(journal: FlightJournal) =
        SessionController(transports = { TransportLease(vehicle.transport()) }, timingFor = { FAST_TIMING }, journal = journal)

    @After fun tearDown() {
        controller.close()
        vehicle.close()
        dir.deleteRecursively()
    }

    private fun Map<String, Any?>.obj(key: String) = this[key] as Map<String, Any?>
    private fun memory() = controller.snapshot().obj("memory")
    private fun runPhase() = (controller.snapshot()["run"] as Map<String, Any?>?)?.get("phase")

    private fun connect() {
        controller.connect(config)
        waitFor("ready") {
            controller.snapshot().obj("vehicle").let { it["connected"] == true && it["homeKnown"] == true && it["positionReady"] == true }
        }
        assertTrue(controller.setExecution(true))
    }

    private fun step(name: String, arguments: Map<String, Any?> = emptyMap()) =
        mapOf("name" to name, "arguments" to arguments, "source" to "rules")

    private fun confirmAndWait(answer: Map<String, Any?>) {
        val token = answer["token"] as String? ?: throw AssertionError("not shown: $answer")
        assertEquals(mapOf("accepted" to true), controller.confirm(token))
        waitFor("plan end", 10_000) { runPhase() == "FINISHED" }
        waitFor("still") { vehicle.speedNow() < 0.05 }
    }

    private fun fly(vararg steps: Map<String, Any?>) =
        confirmAndWait(controller.submitPlan(mapOf("steps" to steps.toList(), "source" to "typed")))

    private fun takeoffAndForward() {
        connect()
        fly(step("takeoff", mapOf("altitude_m" to 3)))
        fly(step("move_relative", mapOf("direction" to "forward", "distance_m" to 3)))
    }

    private fun labels() = (memory()["trail"] as List<Map<String, Any?>>).map { it["label"] }
    private fun plans() = memory()["plans"] as List<Map<String, Any?>>

    @Test fun theSnapshotShowsTheTrailWhereGoBackGoesAndTheRecordedPlans() {
        takeoffAndForward()
        val memory = memory()
        assertEquals(true, memory["homeKnown"])
        assertEquals(listOf("Takeoff", "Move forward 3 m"), labels())
        assertEquals("Takeoff", memory["goBackTo"])
        assertEquals(true, memory["canRetrace"])
        val here = memory.obj("vehicle")
        assertEquals(3.0, here["northM"] as Double, 0.5)
        assertEquals(3.0, here["upM"] as Double, 0.5)
        assertEquals(listOf("Move forward 3 m", "Take off to 3 m"),
            plans().map { (it["steps"] as List<Map<String, Any?>>).single()["description"] })
        assertEquals("typed", plans().first()["source"])
    }

    @Test fun runAgainShowsARecordedPlanForConfirmationWithTheTapSource() {
        takeoffAndForward()
        val planId = plans().first()["planId"] as Long
        val answer = api.call("runAgain", mapOf("planId" to planId)) as SessionApi.Reply.Ok
        val shown = answer.value as Map<String, Any?>
        assertEquals("1. Move forward 3 m", shown["summary"])
        assertEquals("tap", (controller.snapshot()["pending"] as Map<String, Any?>)["source"])
        confirmAndWait(shown)
        assertEquals(listOf("Takeoff", "Move forward 3 m", "Move forward 3 m"), labels())
        // A bare id works too; a plan no longer recorded is an operator error, not a failure.
        assertEquals(mapOf("error" to SessionController.PLAN_GONE), (api.call("runAgain", 999) as SessionApi.Reply.Ok).value)
        assertEquals(SessionException.BAD_ARGS, (api.call("runAgain", null) as SessionApi.Reply.Failed).code)
    }

    @Test fun aRecordedPlanIsValidatedAgainstTheVehicleNow() {
        takeoffAndForward()
        val takeoffPlan = plans().last()["planId"] as Long
        assertEquals(mapOf("error" to "The drone is already flying. Remove the takeoff step."), controller.runAgain(takeoffPlan))
    }

    @Test fun flightMemoryCommandsWorkThroughSubmitPlan() {
        takeoffAndForward()
        val shown = controller.submitPlan(mapOf("steps" to listOf(step("return_home")), "source" to "tap"))
        assertEquals("1. Return home", shown["summary"])
        confirmAndWait(shown)
        assertEquals("Home", labels().last())
        val marked = controller.submitPlan(mapOf("steps" to listOf(step("mark_point", mapOf("point" to 4))), "source" to "tap"))
        assertEquals(true, marked["immediate"])
        assertEquals("COMPLETED", marked["outcome"])
        assertEquals(listOf(4L), (memory()["points"] as List<Map<String, Any?>>).map { (it["point"] as Number).toLong() })
    }

    @Test fun clearMemoryForgetsTheTrailPointsAndPlans() {
        takeoffAndForward()
        assertEquals(SessionApi.Reply.Ok(null), api.call("clearMemory", null))
        val memory = memory()
        assertEquals(true, memory["homeKnown"])
        assertTrue((memory["trail"] as List<*>).isEmpty())
        assertTrue((memory["plans"] as List<*>).isEmpty())
        assertNull(memory["goBackTo"])
    }

    @Test fun theMemorySurvivesARestartOfTheSession() {
        takeoffAndForward()
        val before = controller.journal.state
        controller.close()
        assertTrue(file.isFile)
        controller = newController(journal())
        assertEquals(before, controller.journal.state)
        assertEquals(listOf("Takeoff", "Move forward 3 m"), labels())
    }

    @Test fun missionsAreSavedLoadedAndDeletedThroughTheChannel() {
        connect()
        api.call("missionBuilding", mapOf("enabled" to true))
        controller.submitPlan(mapOf("steps" to listOf(step("move_relative", mapOf("direction" to "left", "distance_m" to 2))),
            "source" to "typed"))
        assertEquals(SessionApi.Reply.Ok(mapOf("saved" to "Left loop")), api.call("saveMission", mapOf("name" to " Left  loop")))
        val saved = (controller.snapshot().obj("mission")["saved"] as List<Map<String, Any?>>).single()
        assertEquals("Left loop", saved["name"])
        assertEquals(1, saved["stageCount"])
        assertEquals(1, saved["stepCount"])
        assertEquals("Move left 2 m", saved["summary"])
        api.call("clearMission", null)
        assertEquals(SessionApi.Reply.Ok(mapOf("loaded" to "Left loop")), api.call("loadMission", mapOf("name" to "left loop")))
        assertEquals(1, (controller.snapshot().obj("mission")["stages"] as List<*>).size)
        assertEquals(mapOf("error" to SafetySupervisor.noSavedMission("Nope")),
            (api.call("loadMission", mapOf("name" to "Nope")) as SessionApi.Reply.Ok).value)
        assertEquals(SessionApi.Reply.Ok(null), api.call("deleteMission", mapOf("name" to "LEFT LOOP")))
        assertTrue((controller.snapshot().obj("mission")["saved"] as List<*>).isEmpty())
        assertEquals(SessionException.BAD_ARGS, (api.call("saveMission", null) as SessionApi.Reply.Failed).code)
    }

    @Test fun aMissionIsBuiltThroughTheChannelAndARecordedOneRunsAgainAsAMission() {
        connect()
        assertEquals(SessionApi.Reply.Ok(null), api.call("missionBuilding", mapOf("enabled" to true)))
        val stage1 = controller.submitPlan(mapOf("steps" to listOf(step("takeoff", mapOf("altitude_m" to 3)),
            step("move_relative", mapOf("direction" to "forward", "distance_m" to 2))), "source" to "typed"))
        assertEquals(mapOf("addedToMission" to true, "stages" to 1), stage1)
        val stage2 = controller.submitPlan(mapOf("steps" to listOf(step("return_home")), "source" to "tap"))
        assertEquals(mapOf("addedToMission" to true, "stages" to 2), stage2)
        val mission = controller.snapshot().obj("mission")
        assertEquals(true, mission["building"])
        assertEquals(listOf("typed", "tap"), (mission["stages"] as List<Map<String, Any?>>).map { it["source"] })
        val shown = (api.call("startMission", null) as SessionApi.Reply.Ok).value as Map<String, Any?>
        val pending = controller.snapshot()["pending"] as Map<String, Any?>
        assertEquals("mission", pending["source"])
        assertEquals(listOf(0L, 0L, 1L), (pending["stageOfStep"] as List<*>).map { (it as Number).toLong() })
        confirmAndWait(shown)
        // The history keeps the stages: run again shows it as a mission (on the ground again it would need its
        // takeoff; in the air the validator refuses the takeoff, as for any plan).
        val recorded = plans().first()
        assertEquals(listOf(0L, 0L, 1L), (recorded["steps"] as List<Map<String, Any?>>).map { (it["stage"] as Number).toLong() })
        assertEquals(mapOf("error" to "The drone is already flying. Remove the takeoff step."),
            controller.runAgain(recorded["planId"] as Long))
    }
}
