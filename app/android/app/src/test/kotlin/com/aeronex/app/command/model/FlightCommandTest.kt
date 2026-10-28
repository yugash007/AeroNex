package com.aeronex.app.command.model

import org.junit.Assert.*
import org.junit.Test

/** Port of the v0.2.0 flight_command_test.dart: the same allowlist, limits and messages. */
class FlightCommandTest {
    private fun call(name: Any?, arguments: Any?) = mapOf("name" to name, "arguments" to arguments)

    private fun rejects(message: String, calls: Any?) {
        val error = assertThrows(calls.toString(), CommandFormatException::class.java) {
            FlightCommand.fromToolCalls(calls)
        }
        assertEquals(calls.toString(), message, error.message)
    }

    @Test fun validatesOneImmutableToolCall() {
        val arguments = mutableMapOf<String, Any?>("altitude_m" to 3)
        val command = FlightCommand.fromToolCalls(listOf(call("takeoff", arguments)))
        arguments["altitude_m"] = 100
        assertEquals("takeoff", command.name)
        assertEquals(mapOf("altitude_m" to 3), command.arguments)
        @Suppress("UNCHECKED_CAST")
        val leaked = command.arguments as MutableMap<String, Any>
        assertThrows(UnsupportedOperationException::class.java) { leaked["altitude_m"] = 100 }
    }

    @Test fun rejectsMalformedCallsWithTheDartMessages() {
        rejects("Exactly one tool call is required.", null)
        rejects("Exactly one tool call is required.", emptyList<Any>())
        rejects("Exactly one tool call is required.", mapOf("name" to "land", "arguments" to emptyMap<String, Any>()))
        rejects("Exactly one tool call is required.",
            listOf(call("land", emptyMap<String, Any>()), call("takeoff", mapOf("altitude_m" to 3))))
        rejects("Expected tool name and arguments only.", listOf("land"))
        rejects("Expected tool name and arguments only.",
            listOf(mapOf("name" to "land", "arguments" to emptyMap<String, Any>(), "execute" to true)))
        rejects("Expected tool name and arguments only.", listOf(mapOf("name" to "land")))
        rejects("Invalid tool name or arguments.", listOf(call(7, emptyMap<String, Any>())))
        rejects("Invalid tool name or arguments.", listOf(call("land", listOf<Any>())))
        rejects("Tool is not allowed.", listOf(call("disarm", emptyMap<String, Any>())))
        rejects("This tool accepts no arguments.", listOf(call("land", mapOf("force" to true))))
    }

    @Test fun rejectsUnsafeSimulatorArguments() {
        val takeoff = "Specify a takeoff altitude of 1–10 m."
        for (arguments in listOf(
            emptyMap(), mapOf("altitude_m" to "3"), mapOf("altitude_m" to Double.NaN),
            mapOf("altitude_m" to Double.POSITIVE_INFINITY), mapOf("altitude_m" to 0), mapOf("altitude_m" to 0.999),
            mapOf("altitude_m" to 11), mapOf("altitude_m" to 10.001), mapOf("altitude_m" to true),
            mapOf("altitude_m" to 3, "speed" to 1),
        )) rejects(takeoff, listOf(call("takeoff", arguments)))
        val move = "Specify a direction and distance >0–10 m."
        for (arguments in listOf(
            mapOf("direction" to "north", "distance_m" to 1), mapOf("direction" to "forward", "distance_m" to 0),
            mapOf("direction" to "forward", "distance_m" to -1), mapOf("direction" to "forward", "distance_m" to 11),
            mapOf("direction" to "forward", "distance_m" to Double.NaN), mapOf("direction" to "forward"),
            mapOf("direction" to "Forward", "distance_m" to 1),
            mapOf("direction" to "forward", "distance_m" to 1, "yaw" to 0),
        )) rejects(move, listOf(call("move_relative", arguments)))
    }

    @Test fun allowsBoundedMovementsAndParameterlessStatusLandHold() {
        for (name in listOf("get_status", "land", "hold")) {
            assertEquals(name, FlightCommand.fromToolCalls(listOf(call(name, emptyMap<String, Any>()))).name)
        }
        for (direction in listOf("forward", "backward", "left", "right", "up", "down")) {
            val command = FlightCommand.of("move_relative", mapOf("direction" to direction, "distance_m" to 10))
            assertEquals(direction, command.direction)
            assertEquals(10.0, command.distanceM!!, 0.0)
        }
        assertEquals(1.0, FlightCommand.of("takeoff", mapOf("altitude_m" to 1)).altitudeM!!, 0.0)
        assertEquals(0.001, FlightCommand.of("move_relative", mapOf("direction" to "up", "distance_m" to 0.001))
            .distanceM!!, 0.0)
        assertNull(FlightCommand.of("land").distanceM)
    }

    @Test fun describesCommandsReadablyAndKeepsTheDartToString() {
        assertEquals("Take off to 3 m", FlightCommand.of("takeoff", mapOf("altitude_m" to 3)).description)
        assertEquals("Take off to 3 m", FlightCommand.of("takeoff", mapOf("altitude_m" to 3.0)).description)
        assertEquals("Take off to 1.4 m", FlightCommand.of("takeoff", mapOf("altitude_m" to 1.4)).description)
        assertEquals("Move forward 3 m",
            FlightCommand.of("move_relative", mapOf("direction" to "forward", "distance_m" to 3)).description)
        assertEquals("Move up 2.5 m",
            FlightCommand.of("move_relative", mapOf("direction" to "up", "distance_m" to 2.5)).description)
        assertEquals("Move backward 10 m",
            FlightCommand.of("move_relative", mapOf("direction" to "backward", "distance_m" to 10)).description)
        assertEquals("Hold position", FlightCommand.of("hold").description)
        assertEquals("Land", FlightCommand.of("land").description)
        assertEquals("Report status", FlightCommand.of("get_status").description)
        assertEquals("takeoff({altitude_m: 3})", FlightCommand.of("takeoff", mapOf("altitude_m" to 3)).toString())
        assertEquals("move_relative({direction: up, distance_m: 2.5})",
            FlightCommand.of("move_relative", mapOf("direction" to "up", "distance_m" to 2.5)).toString())
    }

    /** Spec 2026-10-28: the flight-memory tools carry no coordinates; spots are whole numbers 1–9. */
    @Test fun allowsFlightMemoryToolsWithoutCoordinates() {
        for (name in listOf("return_home", "go_back", "retrace")) {
            val command = FlightCommand.of(name)
            assertTrue(name, command.isMemoryMotion && command.isMotion)
            rejects("This tool accepts no arguments.", listOf(call(name, mapOf("north" to 3.0, "east" to 1.0))))
        }
        assertEquals(2, FlightCommand.of("goto_point", mapOf("point" to 2)).point)
        assertEquals(9, FlightCommand.of("mark_point", mapOf("point" to 9.0)).point)
        assertFalse(FlightCommand.of("mark_point", mapOf("point" to 1)).isMotion)
        assertTrue(FlightCommand.of("move_relative", mapOf("direction" to "up", "distance_m" to 1)).isMotion)
        assertFalse(FlightCommand.of("move_relative", mapOf("direction" to "up", "distance_m" to 1)).isMemoryMotion)
        for (point in listOf<Any?>(0, 10, 2.5, -1, "2", null, Double.NaN)) {
            rejects("Specify a spot number 1–9.", listOf(call("goto_point", mapOf("point" to point))))
            rejects("Specify a spot number 1–9.", listOf(call("mark_point", mapOf("point" to point))))
        }
        rejects("Specify a spot number 1–9.", listOf(call("goto_point", emptyMap<String, Any>())))
        rejects("Specify a spot number 1–9.", listOf(call("goto_point", mapOf("point" to 1, "north" to 2.0))))
        assertEquals("Return home", FlightCommand.of("return_home").description)
        assertEquals("Go back to the previous position", FlightCommand.of("go_back").description)
        assertEquals("Retrace the flown path", FlightCommand.of("retrace").description)
        assertEquals("Go to spot 3", FlightCommand.of("goto_point", mapOf("point" to 3)).description)
        assertEquals("Mark this spot as 3", FlightCommand.of("mark_point", mapOf("point" to 3)).description)
        assertEquals(FlightCommand.MEMORY_MOTION + "mark_point", FlightCommand.MEMORY)
    }
}
