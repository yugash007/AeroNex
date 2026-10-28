package com.aeronex.app.observability

import java.io.IOException
import java.io.Writer
import java.util.concurrent.Executor

/**
 * Writes each [TraceEvent] as one JSON line: `{"t_ns":…,"event":"…","id":…,"fields":{…}}` (spec 5: each session
 * exports JSONL). Formatting happens on the marking thread; [io] runs the write, so pass a background executor
 * for a file (the default writes in place, for tests and in-memory writers). Write errors are counted, not thrown.
 */
class JsonlTraceWriter(private val out: Writer, private val io: Executor = Executor { it.run() }) : (TraceEvent) -> Unit {
    @Volatile var writeErrors = 0L
        private set

    override fun invoke(event: TraceEvent) {
        val text = line(event) + "\n"
        io.execute {
            try {
                synchronized(out) { out.write(text) }
            } catch (_: IOException) {
                writeErrors++
            }
        }
    }

    fun flush() = io.execute {
        try {
            synchronized(out) { out.flush() }
        } catch (_: IOException) {
            writeErrors++
        }
    }

    companion object {
        fun line(event: TraceEvent): String = buildString {
            append("{\"t_ns\":").append(event.atNanos)
            append(",\"event\":")
            string(event.name)
            append(",\"id\":").append(event.id)
            append(",\"fields\":{")
            var first = true
            for ((key, value) in event.fields) {
                if (!first) append(',')
                first = false
                string(key)
                append(':')
                value(value)
            }
            append("}}")
        }

        private fun StringBuilder.value(value: Any?) {
            when (value) {
                null -> append("null")
                is Boolean -> append(value)
                is Double -> if (value.isFinite()) append(value) else append("null")
                is Float -> if (value.isFinite()) append(value) else append("null")
                is Number -> append(value)
                is Enum<*> -> string(value.name)
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
    }
}
