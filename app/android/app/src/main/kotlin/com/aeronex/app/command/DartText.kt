package com.aeronex.app.command

/**
 * Text rules of the v0.2.0 Dart parser that java.util.regex (and ICU, Android's regex engine) do not share. Dart's
 * RegExp follows JavaScript: `\s` is Unicode whitespace, `\b`, `\w` and `\d` are ASCII, and `$` is the very end of
 * the input. The port spells each of these out instead of using the Java escapes, so the JVM tests and the phone
 * agree with the golden vectors. Invisible characters are written as regex `\x{...}` escapes or code points.
 */
internal object DartText {
    /** JavaScript `\s` (WhiteSpace and LineTerminator) as a character-class body. */
    const val WS = """\x{9}-\x{D}\x{20}\x{A0}\x{1680}\x{2000}-\x{200A}\x{2028}\x{2029}\x{202F}\x{205F}\x{3000}\x{FEFF}"""

    /** JavaScript `\w`. */
    const val WORD = "[A-Za-z0-9_]"

    /** Dart `\b` before a word that starts with a [WORD] character. */
    const val START = "(?<![A-Za-z0-9_])"

    /** Dart `\b` after a word that ends with a [WORD] character. */
    const val END = "(?![A-Za-z0-9_])"

    /** U+2026, the ellipsis of the punctuation classes. */
    const val ELLIPSIS = """\x{2026}"""

    /** U+0964, the Devanagari danda. */
    const val DANDA = """\x{964}"""

    /** Hyphen, dash and minus characters (a class body): a sign before a digit, or the joiner of "50-cm". */
    const val DASHES = """\-\x{2010}-\x{2015}\x{2212}\x{FE63}\x{FF0D}"""

    private val whitespaceRun = Regex("[$WS]+")

    /** Dart `String.trim()`: Unicode White_Space and U+FEFF. */
    fun trim(text: String): String = text.trim { it.code in TRIMMED || it.code in 0x2000..0x200A }

    private val TRIMMED = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F,
        0x3000, 0xFEFF)

    /** Dart `text.split(RegExp(r'\s+'))`: leading and trailing empty strings kept. */
    fun splitWhitespace(text: String): List<String> = text.split(whitespaceRun)

    /** Every whitespace run as one space. */
    fun collapseWhitespace(text: String): String = text.replace(whitespaceRun, " ")

    /** A regex alternation of [words], longest first so a shorter word never shadows a longer one. */
    fun alternation(words: Iterable<String>): String =
        words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
}
