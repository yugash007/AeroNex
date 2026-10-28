package com.aeronex.app.session

/**
 * Canonical pretty JSON for the session contract fixtures: maps in insertion order, two-space indent, one trailing
 * newline. Only the types the snapshot may carry over Flutter's StandardMessageCodec are allowed (null, Boolean,
 * Int, Long, finite Double, String, Map with String keys, List), so a fixture also pins the value types.
 */
internal object JsonText {
    fun write(value: Any?): String = StringBuilder().apply { value(value, "") }.append('\n').toString()

    private fun StringBuilder.value(value: Any?, indent: String) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Int, is Long -> append(value)
            is Double -> {
                require(value.isFinite()) { "non-finite number in a snapshot" }
                append(value.toString())
            }
            is String -> string(value)
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    append("{}")
                    return
                }
                append("{\n")
                val inner = "$indent  "
                value.entries.forEachIndexed { i, (key, item) ->
                    require(key is String) { "map key $key is not a String" }
                    append(inner)
                    string(key)
                    append(": ")
                    value(item, inner)
                    append(if (i < value.size - 1) ",\n" else "\n")
                }
                append(indent).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    append("[]")
                    return
                }
                append("[\n")
                val inner = "$indent  "
                value.forEachIndexed { i, item ->
                    append(inner)
                    value(item, inner)
                    append(if (i < value.size - 1) ",\n" else "\n")
                }
                append(indent).append(']')
            }
            else -> error("${value::class.java.name} is not a snapshot value type")
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
}
