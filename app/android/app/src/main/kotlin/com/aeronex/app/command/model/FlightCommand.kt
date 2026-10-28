package com.aeronex.app.command.model

import java.util.Collections

/** A command or plan the operator must rephrase; [message] is the operator-facing v0.2.0 wording. */
class CommandFormatException(message: String) : IllegalArgumentException(message)

/**
 * A validated tool proposal, not authorization to send a flight command.
 * Port of v0.2.0 `flight_command.dart`: the same allowlist, argument limits and messages.
 */
class FlightCommand private constructor(val name: String, arguments: Map<String, Any>) {
    /** Unmodifiable; numbers keep the type they were given (compare them numerically). */
    val arguments: Map<String, Any> = Collections.unmodifiableMap(LinkedHashMap(arguments))

    val altitudeM: Double? get() = (arguments[ALTITUDE] as? Number)?.toDouble()
    val direction: String? get() = arguments[DIRECTION] as? String
    val distanceM: Double? get() = (arguments[DISTANCE] as? Number)?.toDouble()

    /** The saved point (1–9) of [GOTO_POINT] and [MARK_POINT]. */
    val point: Int? get() = (arguments[POINT] as? Number)?.toInt()

    /** A flight-memory step that flies to a remembered position ([MEMORY_MOTION]). */
    val isMemoryMotion: Boolean get() = name in MEMORY_MOTION

    /** The step moves the vehicle horizontally or vertically: a relative move or a flight-memory flight. */
    val isMotion: Boolean get() = name == MOVE || isMemoryMotion

    /** Operator-facing wording, e.g. 'Move forward 3 m'. */
    val description: String
        get() = when (name) {
            TAKEOFF -> "Take off to ${metres(altitudeM!!)} m"
            MOVE -> "Move $direction ${metres(distanceM!!)} m"
            HOLD -> "Hold position"
            LAND -> "Land"
            STATUS -> "Report status"
            RETURN_HOME -> "Return home"
            GO_BACK -> "Go back to the previous position"
            RETRACE -> "Retrace the flown path"
            GOTO_POINT -> "Go to point $point"
            MARK_POINT -> "Mark point $point"
            else -> toString()
        }

    /** Dart's `name({key: value})`. */
    override fun toString() = "$name(${arguments.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value}" }})"

    companion object {
        const val TAKEOFF = "takeoff"
        const val MOVE = "move_relative"
        const val HOLD = "hold"
        const val LAND = "land"
        const val STATUS = "get_status"
        const val ALTITUDE = "altitude_m"
        const val DIRECTION = "direction"
        const val DISTANCE = "distance_m"
        val DIRECTIONS = setOf("forward", "backward", "left", "right", "up", "down")

        // Flight memory (spec 2026-10-28): positions the app flew to and confirmed, resolved by the supervisor from
        // its own journal. They carry no coordinates, so no client or AI can name a raw position.
        const val RETURN_HOME = "return_home"
        const val GO_BACK = "go_back"
        const val RETRACE = "retrace"
        const val GOTO_POINT = "goto_point"
        const val MARK_POINT = "mark_point"
        const val POINT = "point"
        const val MAX_POINT = 9

        /** Flight-memory steps that fly the vehicle. */
        val MEMORY_MOTION = setOf(RETURN_HOME, GO_BACK, RETRACE, GOTO_POINT)

        /** Every flight-memory tool; the AI path never proposes these (spec 2026-10-28 M5). */
        val MEMORY = MEMORY_MOTION + MARK_POINT

        /** Validates exactly one `{name, arguments}` tool call, as v0.2.0 `FlightCommand.fromToolCalls`. */
        fun fromToolCalls(calls: Any?): FlightCommand {
            if (calls !is List<*> || calls.size != 1) throw CommandFormatException("Exactly one tool call is required.")
            val call = calls.single()
            if (call !is Map<*, *> || !call.hasKeys(setOf("name", "arguments"))) {
                throw CommandFormatException("Expected tool name and arguments only.")
            }
            val name = call["name"]
            val args = call["arguments"]
            if (name !is String || args !is Map<*, *>) throw CommandFormatException("Invalid tool name or arguments.")
            when (name) {
                STATUS, LAND, HOLD, RETURN_HOME, GO_BACK, RETRACE ->
                    if (args.isNotEmpty()) throw CommandFormatException("This tool accepts no arguments.")
                GOTO_POINT, MARK_POINT ->
                    if (!args.hasKeys(setOf(POINT)) || !pointNumber(args[POINT])) {
                        throw CommandFormatException("Specify a point number 1–$MAX_POINT.")
                    }
                TAKEOFF ->
                    if (!args.hasKeys(setOf(ALTITUDE)) || !number(args[ALTITUDE], minimum = 1.0)) {
                        throw CommandFormatException("Specify a takeoff altitude of 1–10 m.")
                    }
                MOVE ->
                    if (!args.hasKeys(setOf(DIRECTION, DISTANCE)) ||
                        args[DIRECTION].let { it !is String || it !in DIRECTIONS } ||
                        !number(args[DISTANCE], minimum = 0.0, exclusive = true)
                    ) throw CommandFormatException("Specify a direction and distance >0–10 m.")
                else -> throw CommandFormatException("Tool is not allowed.")
            }
            @Suppress("UNCHECKED_CAST")
            return FlightCommand(name, args as Map<String, Any>)
        }

        /** Shorthand for [fromToolCalls] with one call. */
        fun of(name: String, arguments: Map<String, Any?> = emptyMap()) =
            fromToolCalls(listOf(mapOf("name" to name, "arguments" to arguments)))

        private fun Map<*, *>.hasKeys(keys: Set<String>) = size == keys.size && keys.all { containsKey(it) }

        /** A whole point number 1–[MAX_POINT]. */
        private fun pointNumber(value: Any?): Boolean {
            if (value !is Number) return false
            val v = value.toDouble()
            return v.isFinite() && v % 1.0 == 0.0 && v >= 1 && v <= MAX_POINT
        }

        // Simulator test limits only; real flight needs a separately validated profile.
        private fun number(value: Any?, minimum: Double, exclusive: Boolean = false): Boolean {
            if (value !is Number) return false
            val v = value.toDouble()
            return v.isFinite() && v <= 10 && if (exclusive) v > minimum else v >= minimum
        }

        /** Dart's `_metres`: whole numbers without a decimal point. */
        internal fun metres(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    }
}
