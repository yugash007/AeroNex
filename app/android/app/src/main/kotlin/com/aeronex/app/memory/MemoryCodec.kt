package com.aeronex.app.memory

import com.aeronex.app.vehicle.Ned

/**
 * The flight-memory file format, version [VERSION]: one JSON object. Pure Kotlin (no org.json), so the JVM tests
 * run without Android. [decode] throws [IllegalArgumentException] for anything malformed, a newer version, or a
 * value of the wrong type; the store then starts empty rather than flying to a misread position.
 *
 * ```
 * {"version":1,"nextId":7,"notice":null,"home":{"x":0.0,"y":0.0,"z":0.0},
 *  "trail":[{"x":..,"y":..,"z":..,"label":"Takeoff","at":1730000000000}],
 *  "points":{"1":{"x":..,"y":..,"z":..,"label":"Point 1","at":..}},
 *  "history":[{"id":1,"plan":1,"at":..,"name":"move_relative","arguments":{"direction":"left","distance_m":2},
 *              "description":"Move left 2 m","source":"typed","outcome":"COMPLETED","stage":0}]}
 * ```
 */
object MemoryCodec {
    const val VERSION = 1

    fun encode(state: MemoryState): String = Json.write(linkedMapOf(
        "version" to VERSION,
        "nextId" to state.nextId,
        "notice" to state.notice,
        "home" to state.home?.let(::ned),
        "trail" to state.trail.map(::waypoint),
        "points" to state.points.entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { (n, p) -> n.toString() to waypoint(p) },
        "history" to state.history.map { r ->
            linkedMapOf("id" to r.id, "plan" to r.planId, "at" to r.atWallMs, "name" to r.name, "arguments" to r.arguments,
                "description" to r.description, "source" to r.source, "outcome" to r.outcome, "stage" to r.stage)
        },
    ))

    fun decode(text: String): MemoryState {
        val root = Json.read(text).obj("memory")
        val version = root.long("version")
        require(version in 1..VERSION.toLong()) { "Unsupported memory version $version" }
        return MemoryState(
            home = root["home"]?.let { readNed(it.obj("home")) },
            trail = root.list("trail").map { readWaypoint(it.obj("trail")) },
            points = root.obj("points", "points").entries.associate { (key, value) ->
                val n = key.toIntOrNull()
                require(n != null && n in 1..9) { "Bad point $key" }
                n to readWaypoint(value.obj("point"))
            },
            history = root.list("history").map { item ->
                val r = item.obj("history")
                CommandRecord(
                    id = r.long("id"), planId = r.long("plan"), atWallMs = r.long("at"), name = r.string("name"),
                    arguments = r.obj("arguments", "arguments").mapValues { (key, value) ->
                        when (value) {
                            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else value.toDouble()
                            is Double, is String -> value
                            else -> throw IllegalArgumentException("Bad argument $key")
                        }
                    },
                    description = r.string("description"), source = r.string("source"), outcome = r.string("outcome"),
                    // Missions (2026-10-28) added the stage; a record without one is a plain plan's step.
                    stage = r["stage"]?.let { it as? Long ?: throw IllegalArgumentException("stage is not an integer") }?.toInt() ?: 0,
                )
            },
            nextId = root.long("nextId"),
            notice = root["notice"]?.let { it as? String ?: throw IllegalArgumentException("Bad notice") },
        )
    }

    private fun ned(p: Ned) = linkedMapOf("x" to p.x, "y" to p.y, "z" to p.z)

    private fun waypoint(w: Waypoint) =
        linkedMapOf("x" to w.position.x, "y" to w.position.y, "z" to w.position.z, "label" to w.label, "at" to w.atWallMs)

    private fun readNed(m: Map<String, Any?>) = Ned(m.double("x"), m.double("y"), m.double("z"))

    private fun readWaypoint(m: Map<String, Any?>) = Waypoint(readNed(m), m.string("label"), m.long("at"))

    @Suppress("UNCHECKED_CAST")
    private fun Any?.obj(what: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw IllegalArgumentException("$what is not an object")

    private fun Map<String, Any?>.obj(key: String, what: String) = this[key].obj(what)

    private fun Map<String, Any?>.list(key: String): List<Any?> =
        this[key] as? List<Any?> ?: throw IllegalArgumentException("$key is not a list")

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: throw IllegalArgumentException("$key is not a string")

    private fun Map<String, Any?>.long(key: String): Long =
        this[key] as? Long ?: throw IllegalArgumentException("$key is not an integer")

    private fun Map<String, Any?>.double(key: String): Double {
        val value = when (val raw = this[key]) {
            is Double -> raw
            is Long -> raw.toDouble()
            else -> throw IllegalArgumentException("$key is not a number")
        }
        require(value.isFinite()) { "$key is not finite" }
        return value
    }
}

/**
 * A small JSON writer and reader for [MemoryCodec]: objects are maps (key order kept), arrays lists, integers
 * Long, other numbers Double. Non-finite doubles are written as null.
 */
internal object Json {
    fun write(value: Any?): String = StringBuilder().apply { value(value) }.toString()

    fun read(text: String): Any? = Reader(text).run {
        val value = value()
        space()
        require(at == text.length) { "Trailing text at $at" }
        value
    }

    private fun StringBuilder.value(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Double -> if (value.isFinite()) append(value) else append("null")
            is Float -> if (value.isFinite()) append(value.toDouble()) else append("null")
            is Number -> append(value.toLong())
            is String -> string(value)
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) append(',')
                    first = false
                    string(key.toString())
                    append(':')
                    value(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (item in value) {
                    if (!first) append(',')
                    first = false
                    value(item)
                }
                append(']')
            }
            else -> string(value.toString())
        }
    }

    private fun StringBuilder.string(text: String) {
        append('"')
        for (c in text) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Reader(private val text: String) {
        var at = 0
        private var depth = 0

        fun value(): Any? {
            space()
            require(at < text.length) { "Unexpected end" }
            return when (val c = text[at]) {
                '{' -> nested { obj() }
                '[' -> nested { array() }
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("Unexpected '$c' at $at")
            }
        }

        fun space() {
            while (at < text.length && text[at] in " \t\r\n") at++
        }

        private inline fun <T> nested(read: () -> T): T {
            require(++depth <= MAX_DEPTH) { "Too deep" }
            return read().also { depth-- }
        }

        private fun obj(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            at++
            space()
            if (peek() == '}') return result.also { at++ }
            while (true) {
                space()
                require(peek() == '"') { "Expected a key at $at" }
                val key = string()
                space()
                expect(':')
                result[key] = value()
                space()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw IllegalArgumentException("Expected , or } at ${at - 1}")
                }
            }
        }

        private fun array(): List<Any?> {
            val result = ArrayList<Any?>()
            at++
            space()
            if (peek() == ']') return result.also { at++ }
            while (true) {
                result += value()
                space()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw IllegalArgumentException("Expected , or ] at ${at - 1}")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> when (val e = next()) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            require(at + 4 <= text.length) { "Bad escape" }
                            out.append(text.substring(at, at + 4).toInt(16).toChar())
                            at += 4
                        }
                        else -> throw IllegalArgumentException("Bad escape \\$e")
                    }
                    c < ' ' -> throw IllegalArgumentException("Control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = at
            if (peek() == '-') at++
            while (at < text.length && (text[at].isDigit() || text[at] in ".eE+-")) at++
            val raw = text.substring(start, at)
            val integral = raw.none { it in ".eE" }
            return if (integral) raw.toLongOrNull() ?: raw.toDouble() else raw.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            require(text.startsWith(word, at)) { "Bad literal at $at" }
            at += word.length
            return value
        }

        private fun peek(): Char {
            require(at < text.length) { "Unexpected end" }
            return text[at]
        }

        private fun next(): Char = peek().also { at++ }

        private fun expect(c: Char) {
            require(next() == c) { "Expected '$c' at ${at - 1}" }
        }
    }

    private const val MAX_DEPTH = 32
}
