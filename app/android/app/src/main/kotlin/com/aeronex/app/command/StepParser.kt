package com.aeronex.app.command

import com.aeronex.app.command.DartText.END
import com.aeronex.app.command.DartText.START
import com.aeronex.app.command.DartText.WS
import com.aeronex.app.command.RuleIntentEngine.Intent
import com.aeronex.app.command.RuleIntentEngine.IntentException
import com.aeronex.app.command.model.CommandFormatException
import com.aeronex.app.command.model.FlightCommand
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.grammar.GrammarTables.Plan
import kotlin.math.abs

/**
 * Port of v0.2.0 `StepParser`: one clause → one [FlightCommand], deterministically. Each clause is read with the
 * vocabulary of its own script, and with every other rule vocabulary whose letters it has ("take off करो"); readings
 * that disagree are refused, never chosen between. Words a reading does not account for refuse the clause rather
 * than being dropped. Every table is [GrammarTables.Plan] or a [com.aeronex.app.grammar.Lexicon].
 * Since 0.4.0 a clause is first read through [NaturalPhrasing] ("two point five", "climb 2 m"); messages still quote
 * the clause as said.
 */
object StepParser {
    const val TAKEOFF_HINT = "say a takeoff height, for example: take off to 3 m."
    const val NEGATED = "negated commands are never sent."
    private const val ONE_COMMAND = "say one command per step."
    private const val RESTATE = "say the whole step again, for example: take off to 4 m."
    private val REASONS = linkedMapOf(
        "negated command" to NEGATED,
        "more than one command" to ONE_COMMAND,
        "more than one direction" to "say one direction per step.",
        "no direction found" to "say a direction, for example: move forward 3 m.",
        "negative distance" to "distances and heights cannot be negative.",
        "unsupported distance unit" to "use metres, for example: 3 m.",
        "no distance found" to "say a distance, for example: move forward 3 m.",
        "unsupported number form" to "say a plain number, for example: 2.5 m.",
        "distance_m must satisfy" to "Specify a direction and distance >0–10 m.",
    )

    /**
     * The clause's command, or null when no rule reads one (v0.2.0 handed those to its AI; v2 refuses them).
     * Throws [CommandFormatException] when a command is recognised but invalid, or the clause is a self-correction,
     * a negation or a bare distance.
     */
    fun parse(spoken: String): FlightCommand? {
        // Spec 2026-10-28: a whole clause that is a flight-memory template ("return home", "go to point 2"); anything
        // more ("go back 3 m", "don't go back") is the rules' as before.
        MemoryCommands.read(spoken)?.let { return it }
        // 0.4.0: spoken decimals and halves as digits, direction verbs and comparatives as v0.2.0 words.
        val natural = NaturalPhrasing.read(spoken)
        val clause = TAKEOFF_UP_TO.replace(
            VERB_BEFORE_TAKEOFF.replaceFirst(LIGHT_VERB.replace(natural.text) { Plan.LIGHT_VERB_REPLACEMENT }, ""),
        ) { "${it.groupValues[1]} to" }
        rejectUnitsAndSigns(clause)
        val words = words(clause)
        // Hardening beyond v0.2.0: the te negative imperatives of the recognizer grammars (వెళ్ళకు ...) are
        // negations too; v0.2.0 read no command in them and asked its AI.
        if (words.any { it in Plan.NEGATION_WORDS || it in NEGATED_VERBS }) throw fail(reason("negated command"))
        // "climb back up 2 m", "climb down 2 m": a direction verb or comparative beside another direction.
        if (natural.conflict) throw fail(reason("more than one direction"))
        val read = read(clause)
        if (read == null) {
            rejectQualifiers(words)
            // "…, sorry, four meters": the plan never keeps the withdrawn number, and no step is guessed from a
            // lone distance.
            if (corrects(words)) throw fail(RESTATE)
            if (words.any(::isDistanceWord) && words.all {
                    isDistanceWord(it) || it in Plan.MORE_WORDS || it in Plan.HEDGE_WORDS || it in Plan.FILLER ||
                        it in Plan.INDIC_FILLER
                }
            ) throw fail(reason("no direction found"))
            return if (asksStatus(clause)) tool(FlightCommand.STATUS) else null
        }
        val (intent, code) = read
        // A spoken decimal or half counts only in the vocabulary that reads the step, as whole numerals do
        // (rejectUnreadNumbers): "move डेढ़ meters forward" is refused like "move तीन meters forward".
        if (natural.numbers.any { it != code }) throw fail(reason("unsupported number form"))
        if (asksStatus(clause)) throw fail(ONE_COMMAND)
        return when (intent.command) {
            "TAKEOFF" -> takeoff(clause, code, words)
            "LAND" -> simple(FlightCommand.LAND, words)
            // A spoken stop means stop and hold; motors are never shut off.
            "HOVER", "STOP" -> simple(FlightCommand.HOLD, words)
            RuleIntentEngine.MOVE -> move(intent, clause, code, words)
            else -> throw fail("${intent.command} is not a plan step.")
        }
    }

    /** The command and the vocabulary that read it, or null when no reading finds one. */
    private fun read(clause: String): Pair<Intent, String>? {
        val script = scriptLanguage(clause)
        val lower = clause.lowercase()
        val codes = listOf(script) + RULE_LETTERS.filter { (code, letters) -> code != script && letters.containsMatchIn(lower) }.keys
        val readings = codes.mapNotNull { code -> readAs(clause, code)?.let { code to it } }
        if (readings.isEmpty()) return null
        val (code, reading) = readings.first()
        for ((_, other) in readings.drop(1)) {
            val agree = if (reading is Intent && other is Intent) reading == other
            else reading is CommandFormatException && other is CommandFormatException
            if (!agree) throw fail(ONE_COMMAND)
        }
        if (reading is CommandFormatException) throw reading
        return reading as Intent to code
    }

    /** One vocabulary's reading: an [Intent], a [CommandFormatException] for an invalid command, or null for none. */
    private fun readAs(clause: String, code: String): Any? = try {
        RuleIntentEngine.parse(clause, code)
    } catch (_: RuleIntentEngine.Unsupported) {
        null // No rule vocabulary for this script yet (ta, kn, ml).
    } catch (_: RuleIntentEngine.NotRecognized) {
        // No command, but a truncated number form is still never guessed.
        readable { RuleIntentEngine.explicitMetres(clause, code) }
        null
    } catch (e: IntentException) {
        if (BARE_FLY.matches(DartText.trim(clause))) CommandFormatException(TAKEOFF_HINT) else CommandFormatException(reason(e.message!!))
    }

    private fun takeoff(clause: String, code: String, words: List<String>): FlightCommand {
        // First, so "take off तीन मीटर" gets the number hint, not the height one.
        rejectUnreadNumbers(clause, code, words)
        // A metre word of any rule script counts: speech mixes them ("take off to 3 मीटर"). explicitMetres knows only
        // its own script's metre words, so the number, still read with this vocabulary, is given a plain "m".
        val altitude = readable { RuleIntentEngine.explicitMetres(clause, code) }
            ?: (if (words.any(::isMetreWord)) readable { RuleIntentEngine.explicitMetres("$clause $METRE", code) } else null)
            ?: throw fail(TAKEOFF_HINT)
        val rest = words.filterIndexed { i, _ -> !namesHeight(words, i) }
        rejectQualifiers(rest)
        rejectUnknownWords(rest, Plan.TAKEOFF_WORDS, "take off to 3 m")
        return tool(FlightCommand.TAKEOFF, mapOf(FlightCommand.ALTITUDE to number(altitude)))
    }

    /**
     * An altitude word naming the takeoff height itself, not an order or condition: English before the number
     * ("take off until 3 m"), Hindi and Telugu after the metre word or height noun ("3 मीटर तक", "3 మీటర్ల వరకు").
     */
    private fun namesHeight(words: List<String>, i: Int): Boolean {
        if (words[i] !in Plan.ALTITUDE_WORDS) return false
        return if (RULE_SCRIPT.matches(words[i])) i > 0 && (isMetreWord(words[i - 1]) || words[i - 1] in Plan.ALTITUDE_WORDS)
        else i + 1 < words.size && isNumber(words[i + 1])
    }

    /** Hold and land never carry a number: "hover at 5 m" or "land after 5 seconds" would otherwise run without it. */
    private fun simple(name: String, words: List<String>): FlightCommand {
        if (words.any(::isNumber)) throw fail("hold and land take no number; $ONE_COMMAND")
        rejectQualifiers(words)
        val land = name == FlightCommand.LAND
        rejectUnknownWords(words, if (land) Plan.LAND_WORDS else Plan.SIMPLE_WORDS, if (land) "land" else "hold position")
        return tool(name)
    }

    /**
     * Takeoff, land and hold keep only [allowed] and filler words, metre and numeral words, and Indic fillers. Any
     * other word may be a second action, a duration or a comparison, so it refuses the clause. A word of a script no
     * rule vocabulary checks is refused too: "land பண்ணாதே" is "don't land".
     */
    private fun rejectUnknownWords(words: List<String>, allowed: Set<String>, example: String) {
        for (i in words.indices) {
            val word = LEADING_NUMBER.replaceFirst(words[i], "") // "3m"
            val rightHere = word == RIGHT && i + 1 < words.size && words[i + 1] in Plan.RIGHT_HERE_FOLLOWERS
            if (word.isEmpty() || rightHere || word in allowed || word in Plan.FILLER || word in Plan.UNITS ||
                word in Plan.NUMBER_WORDS || isIndicFiller(words, i)
            ) continue
            throw fail(notUnderstood(word, example))
        }
    }

    /**
     * A move keeps only words its reading accounts for: verbs, one direction, the number, metre words, filler and
     * "straight" beside a forward direction. Beside a metre word it also keeps Indic fillers; without one, an Indic
     * word outside the rule tables may name another unit ("5 पलों"). Any other word refuses the clause.
     */
    private fun move(intent: Intent, clause: String, code: String, words: List<String>): FlightCommand {
        val direction = intent.direction!!.lowercase()
        if ((direction == "up" || direction == "down") && words.any { it in Plan.ALTITUDE_WORDS }) {
            throw fail("say how far to move, for example: move up 2 m.")
        }
        rejectQualifiers(words)
        rejectUnreadNumbers(clause, code, words)
        if (words.count { it in Plan.DIRECTION_WORDS.getValue(code) } > 1) throw fail(ONE_COMMAND)
        val metres = words.any(::isMetreWord)
        for (i in words.indices) {
            val word = LEADING_NUMBER.replaceFirst(words[i], "") // "3m"
            if (word.isEmpty() || word in Plan.UNITS || word in Plan.FILLER || code == "en" && word in Plan.ENGLISH_MOVE_WORDS) {
                continue
            }
            if (otherVocabulary(Plan.DIRECTION_WORDS, code, word)) throw fail(reason("more than one direction"))
            // "go straight ahead", "सीधा आगे", "నేరుగా ముందుకు".
            if (word in Plan.STRAIGHT_WORDS && listOfNotNull(words.getOrNull(i - 1), words.getOrNull(i + 1)).any { it in Plan.FORWARD_WORDS }) {
                continue
            }
            if (RULE_SCRIPT.matches(word)) {
                if (word in Plan.INDIC_MOVE_WORDS || metres && isIndicFiller(words, i)) continue
                throw fail(if (metres) notUnderstood(word, "move forward 3 m") else reason("unsupported distance unit"))
            }
            // "go forward for 2 meters": "for" directly before a metre distance.
            val forDistance = word == FOR && metres && i + 1 < words.size && isNumber(words[i + 1])
            if (!forDistance && !beforeDirection(words, i)) throw fail(notUnderstood(word, "move forward 3 m"))
        }
        return tool(FlightCommand.MOVE, mapOf(FlightCommand.DIRECTION to direction, FlightCommand.DISTANCE to number(intent.distance!!)))
    }

    private fun isIndicFiller(words: List<String>, i: Int): Boolean {
        val rule = Plan.CONTEXT_FILLERS.firstOrNull { it.word == words[i] } ?: return words[i] in Plan.INDIC_FILLER
        return i > 0 && words[i - 1] in rule.after || i + 1 < words.size && words[i + 1] in rule.before
    }

    private fun corrects(words: List<String>) =
        words.any { it in Plan.CORRECTION_WORDS } || CORRECTION_PHRASE.containsMatchIn(words.joinToString(" ") + " ")

    private fun otherVocabulary(tables: Map<String, Set<String>>, code: String, word: String) =
        tables.any { (key, table) -> key != code && word in table }

    private fun isNumber(word: String) = ANY_NUMBER.containsMatchIn(word) || word in Plan.NUMBER_WORDS || word in Plan.SCALE_WORDS

    /** A metre word of any rule script, glued digits allowed ("3m", "3मीटर"). */
    private fun isMetreWord(word: String) = LEADING_NUMBER.replaceFirst(word, "") in Plan.UNITS

    private fun isDistanceWord(word: String) = isNumber(word) || isMetreWord(word)

    /** "to the right", "towards your left": a preposition before a direction. */
    private fun beforeDirection(words: List<String>, i: Int): Boolean {
        if (words[i] !in Plan.BEFORE_DIRECTION) return false
        var next = i + 1
        while (next < words.size && words[next] in Plan.BEFORE_DIRECTION_SKIP) next++
        return next < words.size && words[next] in Plan.DIRECTION_WORDS.getValue("en")
    }

    /** A number the [code] vocabulary did not read: another language's numeral word, or a second digit group. */
    private fun rejectUnreadNumbers(clause: String, code: String, words: List<String>) {
        if (DIGIT_GROUP.findAll(clause).count() > 1 || words.any { otherVocabulary(Plan.NUMERAL_WORDS, code, it) }) {
            throw fail(reason("unsupported number form"))
        }
    }

    /** A duration, angle, rate, count, condition or ordering word in any script: never dropped silently. */
    private fun rejectQualifiers(words: List<String>) {
        for (i in words.indices) {
            val counted = LEADING_NUMBER.replaceFirst(words[i], "")
            if (words[i] in Plan.MEASURE_WORDS ||
                counted in Plan.COUNTED_WORDS && (counted != words[i] || i > 0 && isNumber(words[i - 1]))
            ) throw fail(reason("unsupported distance unit"))
        }
        if (words.any { it in Plan.SEQUENCE_WORDS }) throw fail(ONE_COMMAND)
    }

    /** A non-metre unit, a minus sign or a scale or fraction word in any script refuses the clause before it is read. */
    private fun rejectUnitsAndSigns(clause: String) {
        val words = words(clause).map { LEADING_NUMBER.replaceFirst(it, "") } // "5cm"
        if (words.any { it in Plan.MINUS_WORDS } || SIGNED_NUMBER.containsMatchIn(clause)) throw fail(reason("negative distance"))
        if (words.any { it in Plan.OTHER_UNITS } || UNIT_SYMBOL.containsMatchIn(clause)) throw fail(reason("unsupported distance unit"))
        if (words.any { it in Plan.SCALE_WORDS }) throw fail(reason("unsupported number form"))
    }

    /**
     * The script-independent part of [rejectUnitsAndSigns] for the template matcher: a signed number ("-3", "– 3")
     * or a unit symbol after a number ("5'", "5%") throws [CommandFormatException] with the same step message.
     */
    internal fun rejectSignsAndSymbols(clause: String) {
        if (SIGNED_NUMBER.containsMatchIn(clause)) throw fail(reason("negative distance"))
        if (UNIT_SYMBOL.containsMatchIn(clause)) throw fail(reason("unsupported distance unit"))
    }

    private fun notUnderstood(word: String, example: String) = "\"$word\" is not understood; say for example: $example."

    /** Lower-case words of [clause], split at anything but letters, marks, digits and joiners. */
    private fun words(clause: String) = WORD_BREAK.split(clause.lowercase()).filter { it.isNotEmpty() }

    private fun asksStatus(clause: String) = NON_WORD.split(clause.lowercase()).any { it in Plan.STATUS_WORDS }

    private fun tool(name: String, arguments: Map<String, Any?> = emptyMap()) = FlightCommand.of(name, arguments)

    private inline fun <T> readable(read: () -> T): T = try {
        read()
    } catch (e: IntentException) {
        throw fail(reason(e.message!!))
    }

    private fun reason(message: String) = REASONS.entries.firstOrNull { message.startsWith(it.key) }?.value ?: message

    private fun fail(message: String) = CommandFormatException(message)

    /** Whole numbers as Int (as v0.2.0 `toInt()`), the rest as Double. */
    private fun number(value: Double): Number = if (value % 1.0 == 0.0 && abs(value) <= Int.MAX_VALUE) value.toInt() else value

    /** The Indic script with the most letters (ties in te, hi, ta, kn, ml order), else English. */
    private fun scriptLanguage(clause: String): String {
        val counts = LinkedHashMap<String, Int>()
        for (code in Plan.SCRIPT_TIE_ORDER) counts[code] = 0
        for (letter in LETTER.findAll(clause)) {
            val cp = letter.value.codePointAt(0)
            for (code in Plan.SCRIPT_TIE_ORDER) {
                val lexicon = GrammarTables.lexicon(code)
                if (cp in lexicon.scriptFirst..lexicon.scriptLast) counts[code] = counts.getValue(code) + 1
            }
        }
        var best = "en"
        var most = 0
        for ((code, count) in counts) if (count > most) {
            best = code
            most = count
        }
        return best
    }

    private const val RIGHT = "right"
    private const val FOR = "for"
    private val METRE = GrammarTables.lexicon("en").unitAbbreviations.first()

    /** Letters of each script a rule vocabulary covers. */
    private val RULE_LETTERS: Map<String, Regex> = GrammarTables.RULE_LANGUAGES.associateWith { code ->
        GrammarTables.lexicon(code).let { Regex("[\\x{%X}-\\x{%X}]".format(it.scriptFirst, it.scriptLast)) }
    }

    /** Devanagari or Telugu only (with joiners): the non-Latin scripts whose rule tables were checked. */
    private val RULE_SCRIPT = Regex(
        GrammarTables.RULE_LANGUAGES.map(GrammarTables::lexicon).filter { it.scriptFirst > 0x7F }
            .joinToString("", "[", """\x{200C}\x{200D}]+""") { "\\x{%X}-\\x{%X}".format(it.scriptFirst, it.scriptLast) },
    )

    /** The light verbs "कर दो", "करवा दो", "कर दीजिए" … mean "करो"; that "दो" is not two. */
    private val LIGHT_VERB = Regex(
        """(?<![\p{L}\p{M}])(?:""" + DartText.alternation(Plan.LIGHT_VERB_STEMS) + ")[$WS]+(?:" +
            DartText.alternation(Plan.LIGHT_VERB_AUXILIARIES) + """)(?![\p{L}\p{M}])""",
    )

    /** "take off up to 3 m" names the takeoff height, not a climb. */
    private val TAKEOFF_UP_TO = Regex("""${START}(take[$WS]*off|launch)[$WS]+up[$WS]+to$END""", RegexOption.IGNORE_CASE)

    /** The "fly" of "hey drone, fly, take off to 3 m" is no second command. */
    private val VERB_BEFORE_TAKEOFF = Regex("""^[$WS]*(?:fly|go|move)[$WS]+(?=(?:take[$WS]*off|launch)$END)""", RegexOption.IGNORE_CASE)
    private val BARE_FLY = Regex("""fly[.!?]*""", RegexOption.IGNORE_CASE)
    private val CORRECTION_PHRASE = Regex(
        "(?:^| )(?:" + DartText.alternation(Plan.CORRECTION_PHRASES_ANYWHERE) + ") |^(?:" +
            DartText.alternation(Plan.CORRECTION_PHRASES_LEADING) + ") ",
    )
    private val SIGNED_NUMBER = Regex("""(?<!\p{Nd})[${DartText.DASHES}][$WS]*\p{Nd}""")

    /** A unit, angle or rate symbol after a number: "9°", "5'", "5 %", "2 m/s". */
    private val UNIT_SYMBOL =
        Regex("""\p{Nd}[$WS]*(?:[\p{S}'"\x{2018}\x{2019}\x{201C}\x{201D}\x{2032}\x{2033}\x{2034}%\x{2030}\x{2031}]|\p{L}{1,2}[$WS]*/)""")
    private val DIGIT_GROUP = Regex("""\p{Nd}+(?:[.,]\p{Nd}+)*""")
    private val LEADING_NUMBER = Regex("""^\p{N}+""")
    private val ANY_NUMBER = Regex("""\p{N}""")
    private val LETTER = Regex("""[\p{L}\p{M}]""")
    private val WORD_BREAK = Regex("""[^\p{L}\p{M}\p{N}\x{200C}\x{200D}]+""")
    private val NON_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")

    /** The te negative imperatives (and any other one-word grammar negation) of every lexicon. */
    private val NEGATED_VERBS: Set<String> = GrammarTables.lexicons.values
        .flatMap { it.grammarNegators }
        .filter { WORD_BREAK.split(it.lowercase()).filter(String::isNotEmpty).size == 1 }
        .map { it.lowercase() }
        .toSet()
}
