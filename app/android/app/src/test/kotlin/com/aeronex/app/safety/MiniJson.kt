package com.aeronex.app.safety

/**
 * Minimal JSON reader: objects become maps, integers Long, other numbers Double.
 * A copy of the test helper link.mavlink.MiniJson, so this package does not depend on that one.
 */
internal class MiniJson private constructor(private val text: String) {
    private var index = 0

    companion object {
        fun parse(text: String): Any? = MiniJson(text).run {
            val value = value()
            skipSpace()
            require(index == text.length) { "Trailing JSON at $index" }
            value
        }
    }

    private fun value(): Any? {
        skipSpace()
        return when (val c = text[index]) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at $index")
        }
    }

    private fun obj(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        index++
        skipSpace()
        if (text[index] == '}') return result.also { index++ }
        while (true) {
            skipSpace()
            val key = string()
            skipSpace()
            expect(':')
            result[key] = value()
            skipSpace()
            if (text[index++] == '}') return result
            require(text[index - 1] == ',') { "Expected ',' at ${index - 1}" }
        }
    }

    private fun array(): List<Any?> {
        val result = ArrayList<Any?>()
        index++
        skipSpace()
        if (text[index] == ']') return result.also { index++ }
        while (true) {
            result += value()
            skipSpace()
            if (text[index++] == ']') return result
            require(text[index - 1] == ',') { "Expected ',' at ${index - 1}" }
        }
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            when (val c = text[index++]) {
                '"' -> return out.toString()
                '\\' -> when (val e = text[index++]) {
                    'u' -> out.append(text.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                    'n' -> out.append('\n')
                    't' -> out.append('\t')
                    'r' -> out.append('\r')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    else -> out.append(e)
                }
                else -> out.append(c)
            }
        }
    }

    private fun number(): Any {
        val start = index
        while (index < text.length && text[index] in "+-0123456789.eE") index++
        val token = text.substring(start, index)
        return if (token.any { it in ".eE" }) token.toDouble() else token.toLong()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, index)) { "Expected $word at $index" }
        index += word.length
        return value
    }

    private fun expect(c: Char) {
        require(text[index] == c) { "Expected '$c' at $index" }
        index++
    }

    private fun skipSpace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
