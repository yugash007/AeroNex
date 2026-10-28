package com.aeronex.app.session

import com.aeronex.app.safety.StopPolicy
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("UNCHECKED_CAST")
class SessionApiTest {
    private val vehicle = FakeArduCopter()
    private val controller = SessionController(transports = { TransportLease(vehicle.transport()) }, timingFor = { FAST_TIMING })
    private val api = SessionApi(controller)
    private val udp = mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0,
        "radioLink" to false)

    @After fun tearDown() {
        controller.close()
        vehicle.close()
    }

    private fun ok(method: String, arguments: Any? = null): Any? {
        val reply = api.call(method, arguments)
        assertTrue("$method: $reply", reply is SessionApi.Reply.Ok)
        return (reply as SessionApi.Reply.Ok).value
    }

    private fun snapshot() = ok("getSnapshot") as Map<String, Any?>

    @Test fun unknownMethodsAreNotImplemented() {
        assertEquals(SessionApi.Reply.NotImplemented, api.call("selfDestruct", null))
    }

    @Test fun pushToTalkTheLanguageAndTheFlightScreenFlag() {
        // T12: pttDown/pttUp are implemented now (they were reserved in Phase 1a). Without voice they do nothing.
        assertEquals(SessionApi.Reply.Ok(null), api.call("pttDown", null))
        assertEquals(SessionApi.Reply.Ok(null), api.call("pttUp", null))
        assertEquals(mapOf("language" to "hi"), ok("setLanguage", mapOf("language" to "hi-IN")))
        assertEquals("hi", (snapshot()["speech"] as Map<String, Any?>)["language"])
        assertEquals(SessionException.BAD_ARGS, (api.call("setLanguage", mapOf("language" to "xx")) as SessionApi.Reply.Failed).code)
        assertEquals(SessionException.BAD_ARGS, (api.call("setLanguage", null) as SessionApi.Reply.Failed).code)
        assertFalse(controller.flightScreenVisible)
        assertEquals(SessionApi.Reply.Ok(null), api.call("flightScreen", mapOf("visible" to true)))
        assertTrue(controller.flightScreenVisible)
        assertEquals(SessionApi.Reply.Ok(null), api.call("flightScreen", false))
        assertFalse(controller.flightScreenVisible)
    }

    @Test fun stopCancelAndTheAlarmOnlyPostSoTheyAnswerOnTheCallingThread() {
        for (method in listOf("stop", "cancel", "acknowledgeAlarm", "pttDown", "pttUp", "flightScreen")) {
            assertTrue(method, SessionApi.isInline(method))
        }
        // submitText waits for the supervisor like submitPlan, so it runs on the channel's worker (T10).
        for (method in listOf("connect", "disconnect", "setExecution", "submitPlan", "submitText", "confirm", "requestTelemetry",
            "getSnapshot")) {
            assertFalse(method, SessionApi.isInline(method))
        }
    }

    @Test fun connectReturnsTheSnapshotAndBadSettingsAreErrors() {
        val bad = api.call("connect", udp + ("remotePort" to 0))
        assertEquals(SessionApi.Reply.Failed(SessionException.BAD_ARGS, SessionConfig.BAD_REMOTE_PORT), bad)
        val first = ok("connect", udp) as Map<String, Any?>
        assertEquals("udp", (first["link"] as Map<String, Any?>)["transport"])
        assertEquals(SessionException.ALREADY_CONNECTED, (api.call("connect", udp) as SessionApi.Reply.Failed).code)
        waitFor("up") { (snapshot()["link"] as Map<String, Any?>)["state"] == "up" }
        assertEquals(null, ok("disconnect"))
        assertEquals("down", (snapshot()["link"] as Map<String, Any?>)["state"])
    }

    @Test fun setExecutionTakesABoolOrAMapAndAnswersTheState() {
        assertEquals(mapOf("enabled" to false), ok("setExecution", true)) // no link yet: stays dry run
        ok("connect", udp)
        assertEquals(mapOf("enabled" to true), ok("setExecution", true))
        assertEquals(mapOf("enabled" to false), ok("setExecution", mapOf("enabled" to false)))
        assertEquals(SessionException.BAD_ARGS, (api.call("setExecution", "yes") as SessionApi.Reply.Failed).code)
    }

    @Test fun submitConfirmAndCancelGoThroughTheController() {
        ok("connect", udp)
        val plan = mapOf("steps" to listOf(mapOf("name" to "takeoff", "arguments" to mapOf("altitude_m" to 3), "source" to "rules")),
            "source" to "tap")
        val shown = ok("submitPlan", plan) as Map<String, Any?>
        assertEquals("1. Take off to 3 m", shown["summary"])
        assertEquals("tap", (snapshot()["pending"] as Map<String, Any?>)["source"])
        assertEquals(null, ok("cancel"))
        waitFor("dropped") { snapshot()["pending"] == null }
        assertEquals(mapOf("accepted" to false, "error" to "No plan is waiting for confirmation."),
            ok("confirm", shown["token"]))
        val again = ok("submitPlan", plan) as Map<String, Any?>
        assertEquals(mapOf("accepted" to true), ok("confirm", mapOf("token" to again["token"])))
        assertEquals(SessionException.BAD_ARGS, (api.call("submitPlan", "take off") as SessionApi.Reply.Failed).code)
        assertEquals(SessionException.BAD_ARGS, (api.call("confirm", 42) as SessionApi.Reply.Failed).code)
    }

    @Test fun stopIsTheOnScreenButtonWithItsHold() {
        assertEquals(null, ok("stop", mapOf("heldOneSecond" to true)))
        waitFor("decided") { snapshot()["stop"] != null }
        val stop = snapshot()["stop"] as Map<String, Any?>
        assertEquals("BUTTON", stop["trigger"])
        assertEquals(StopPolicy.NOT_CONNECTED, stop["message"])
        assertEquals(null, ok("stop", null)) // no arguments: a press without the hold
        assertEquals(null, ok("acknowledgeAlarm"))
    }

    @Test fun requestTelemetryAnswersWhetherItWasQueued() {
        assertEquals(false, ok("requestTelemetry"))
        ok("connect", udp)
        assertEquals(true, ok("requestTelemetry"))
    }
}
