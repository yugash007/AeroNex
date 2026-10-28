package com.aeronex.app.session

import com.aeronex.app.command.PlanParser
import com.aeronex.app.command.TranscriptInspector
import com.aeronex.app.command.UnkPolicy
import com.aeronex.app.vehicle.executor.FAST_TIMING
import com.aeronex.app.vehicle.executor.FakeArduCopter
import com.aeronex.app.vehicle.executor.waitFor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `submitText(text, language)`: the Kotlin parser, then the supervisor exactly as `submitPlan`. */
@Suppress("UNCHECKED_CAST")
class SessionSubmitTextTest {
    private val vehicle = FakeArduCopter()
    private val controller = SessionController(transports = { TransportLease(vehicle.transport()) }, timingFor = { FAST_TIMING })
    private val api = SessionApi(controller)
    private val udp = mapOf("transport" to "udp", "host" to "127.0.0.1", "remotePort" to 14550, "localPort" to 0)

    @After fun tearDown() {
        controller.close()
        vehicle.close()
    }

    private fun ok(method: String, arguments: Any?): Map<String, Any?> {
        val reply = api.call(method, arguments)
        assertTrue("$method: $reply", reply is SessionApi.Reply.Ok)
        return (reply as SessionApi.Reply.Ok).value as Map<String, Any?>
    }

    private fun text(text: String, language: String = "en-IN", source: String? = null) =
        ok("submitText", listOfNotNull("text" to text, "language" to language, source?.let { "source" to it }).toMap())

    private fun connect() {
        ok("connect", udp)
        waitFor("link up with a ready vehicle") {
            val snapshot = controller.snapshot()
            val vehicle = snapshot["vehicle"] as Map<String, Any?>
            (snapshot["link"] as Map<String, Any?>)["state"] == "up" && vehicle["connected"] == true &&
                vehicle["homeKnown"] == true && vehicle["positionReady"] == true
        }
    }

    @Test fun motionIsShownForConfirmationWithTheTypedSource() {
        connect()
        val shown = text("take off to 3 m then move forward 2 m")
        assertEquals("1. Take off to 3 m; 2. Move forward 2 m", shown["summary"])
        val pending = controller.snapshot()["pending"] as Map<String, Any?>
        assertEquals(shown["token"], pending["token"])
        assertEquals("typed", pending["source"])
        assertEquals(mapOf("accepted" to true), ok("confirm", mapOf("token" to shown["token"])))
    }

    @Test fun holdStopAndStatusRunAtOnce() {
        connect()
        for (command in listOf("hold position", "stop", "रुको")) {
            assertEquals(command, true, text(command, if (command == "रुको") "hi-IN" else "en-IN")["immediate"])
        }
        assertEquals("NOT_SENT", text("status")["outcome"])
        assertNull(controller.snapshot()["pending"])
    }

    @Test fun rejectionsAreErrorAnswersWithTheParserMessage() {
        connect()
        assertEquals(mapOf("error" to TranscriptInspector.NEGATION), text("don't take off to 3 m"))
        assertEquals(mapOf("error" to TranscriptInspector.NEEDS_HEIGHT), text("take off"))
        assertEquals(mapOf("error" to PlanParser.notUnderstood(1, "do a flip")), text("do a flip"))
        assertEquals(mapOf("error" to UnkPolicy.SAY_AGAIN), text("[unk] what is the [unk] fly"))
        assertEquals(mapOf("error" to "The drone is on the ground. Start with a takeoff height, for example: " +
            "take off to 3 m and fly forward 3 m."), text("move forward 2 m"))
        assertNull(controller.snapshot()["pending"])
    }

    @Test fun theLanguageMayComeAsLangAndTheSourceIsOptional() {
        connect()
        val shown = ok("submitText", mapOf("text" to "3 మీటర్లు టేకాఫ్", "lang" to "te", "source" to "phone"))
        assertEquals("1. Take off to 3 m", shown["summary"])
        assertEquals("phone", (controller.snapshot()["pending"] as Map<String, Any?>)["source"])
    }

    @Test fun tamilKannadaAndMalayalamTextIsReadByTheirTemplates() {
        connect()
        // v0.2.0 had no ta/kn/ml rules: text in the selected language's script is read by its CTC templates, so typed
        // commands work in every language without a speech pack (spec 4.10, 7.1).
        for ((typed, language) in listOf("மூன்று மீட்டர் டேக் ஆஃப்" to "ta-IN", "ಮೂರು ಮೀಟರ್ ಟೇಕ್ ಆಫ್" to "kn",
            "മൂന്ന് മീറ്റർ ടേക്ക് ഓഫ്" to "Malayalam")) {
            assertEquals(typed, "1. Take off to 3 m", text(typed, language)["summary"])
        }
        assertEquals(true, text("நிறுத்து", "ta-IN")["immediate"])
        assertEquals(mapOf("error" to TranscriptInspector.NEGATION), text("மூன்று மீட்டர் டேக் ஆஃப் வேண்டாம்", "ta-IN"))
        assertEquals(mapOf("error" to PlanParser.notUnderstood(1, "மூன்று மீட்டர் டேக் ஆஃப் பண்ணாதே")),
            text("மூன்று மீட்டர் டேக் ஆஃப் பண்ணாதே", "ta-IN"))
        // Text in another script still goes to the rules.
        assertEquals("1. Take off to 3 m", text("take off to 3 m", "ta-IN")["summary"])
    }

    @Test fun malformedRequestsAreBadArguments() {
        for (arguments in listOf("take off", mapOf("text" to "land"), mapOf("language" to "en"),
            mapOf("text" to "land", "language" to "en", "source" to "ai"), mapOf("text" to "land", "language" to "xx"))) {
            val reply = api.call("submitText", arguments)
            assertEquals("$arguments", SessionException.BAD_ARGS, (reply as SessionApi.Reply.Failed).code)
        }
    }
}
