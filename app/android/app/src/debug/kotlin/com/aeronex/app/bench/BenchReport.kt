package com.aeronex.app.bench

import com.aeronex.app.command.ParseResult
import com.aeronex.app.observability.LatencyStats
import kotlin.math.roundToLong

/** Where the numbers were measured. An emulator is never representative of the spec 10.1 reference phones. */
enum class MeasuredOn { EMULATOR, DEVICE }

object BenchEnvironment {
    private val EMULATOR_HARDWARE = setOf("ranchu", "goldfish")

    /** From `Build.FINGERPRINT`, `Build.HARDWARE`, `Build.PRODUCT` and `Build.MODEL`. */
    fun measuredOn(fingerprint: String, hardware: String, product: String, model: String): MeasuredOn {
        val emulator = hardware in EMULATOR_HARDWARE || fingerprint.startsWith("generic") ||
            fingerprint.contains("/sdk_") || product.startsWith("sdk") || product.contains("_sdk") ||
            model.startsWith("sdk_gphone") || model.contains("Android SDK built for") || model.contains("Emulator")
        return if (emulator) MeasuredOn.EMULATOR else MeasuredOn.DEVICE
    }

    fun note(measuredOn: MeasuredOn): String = when (measuredOn) {
        MeasuredOn.EMULATOR -> "EMULATOR (x86_64 guest on a desktop CPU): every number here is not representative of " +
            "the R0-R5 reference phones (spec 10.1); the spec Tier gates (6.2, 6.3) stay pending real phones."
        MeasuredOn.DEVICE -> "A phone: representative only if it is one of the spec 10.1 reference phones (R0-R5); " +
            "check environment.model. Clips are eSpeak TTS, so accuracy says nothing about real speakers."
    }
}

/** One engine's run: load, memory before load / after load / after decode, every clip, extra probes or the error. */
data class EngineRun(
    val unit: BenchUnit,
    val engine: String,
    val threads: Int?,
    val loadMs: Double?,
    val memory: List<MemorySample>,
    val clips: List<ClipOutcome>,
    val probes: Map<String, Any?> = emptyMap(),
    val error: String? = null,
)

/** The exported JSON (`getExternalFilesDir(null)/bench/<timestamp>.json`); every engine, summary and clip is labelled. */
object BenchReport {
    const val SCHEMA = "aeronex-bench/1"

    fun build(
        measuredOn: MeasuredOn,
        environment: Map<String, Any?>,
        settings: Map<String, Any?>,
        install: List<Map<String, Any?>>,
        skipped: Map<String, String>,
        baseline: MemorySample?,
        runs: List<EngineRun>,
        startedAt: String,
        finishedAt: String,
        error: String? = null,
    ): Map<String, Any?> = linkedMapOf(
        "schema" to SCHEMA,
        "measuredOn" to measuredOn,
        "representativeOfR0R5" to if (measuredOn == MeasuredOn.EMULATOR) false else null,
        "note" to BenchEnvironment.note(measuredOn),
        "startedAt" to startedAt,
        "finishedAt" to finishedAt,
        "environment" to environment,
        "settings" to settings,
        "install" to install,
        "skipped" to skipped,
        "baseline" to baseline?.let(::memory),
        "engines" to runs.map { engine(it, measuredOn) },
        "error" to error,
    )

    private fun engine(run: EngineRun, on: MeasuredOn): Map<String, Any?> = linkedMapOf(
        "measuredOn" to on,
        "id" to run.unit.id,
        "kind" to run.unit.kind.wire,
        "language" to run.unit.language,
        "pack" to run.unit.packId,
        "engine" to run.engine,
        "threads" to run.threads,
        "loadMs" to run.loadMs?.let { round(it, 1) },
        "error" to run.error,
        "summary" to summary(run, on),
        "memory" to run.memory.map(::memory),
        "probes" to run.probes,
        "clips" to run.clips.map { clip(it, on) },
    )

    private fun summary(run: EngineRun, on: MeasuredOn): Map<String, Any?> {
        val ptt = run.clips.mapNotNull { it.ptt }
        val ended = ptt.filter { it.status == PttStatus.ENDED }
        val spotted = run.clips.filter { it.spotter != null }
        val memory = run.memory.associateBy { it.label }
        return linkedMapOf<String, Any?>("measuredOn" to on, "clips" to run.clips.size).apply {
            if (ptt.isNotEmpty()) {
                this["verdicts"] = Verdict.entries.associate { v -> v.name to run.clips.count { it.verdict == v } }
                this["statuses"] = PttStatus.entries.associate { s -> s.name to ptt.count { it.status == s } }
            }
            this["pttUpToFinalMs"] = latency(ended.mapNotNull { it.pttUpToFinalMs })
            this["pttUpToParsedMs"] = latency(ended.mapNotNull { it.pttUpToParsedMs })
            this["drainedToFinalMs"] = latency(ended.mapNotNull { it.drainedToFinalMs })
            this["rtf"] = ratio(ended.mapNotNull { it.rtf })
            if (spotted.isNotEmpty()) {
                val stops = spotted.filter { it.clip.id == STOP }
                val hits = stops.filter { it.spotterVerdict == SpotterVerdict.HIT }
                this["spotter"] = linkedMapOf(
                    "stopClips" to stops.size,
                    "hits" to hits.size,
                    "otherClips" to spotted.size - stops.size,
                    "falseFires" to spotted.count { it.spotterVerdict == SpotterVerdict.FALSE_FIRE },
                    "firstAfterReleaseMs" to latency(hits.mapNotNull { it.spotter?.firstAfterReleaseMs }),
                    "rtf" to ratio(spotted.mapNotNull { it.spotter?.rtf }),
                ).apply { if (this["rtf"] == null) remove("rtf") }
            }
            val before = memory[BEFORE_LOAD]
            val loaded = memory[AFTER_LOAD]
            this["loadPrivateDirtyPlusSwapKb"] =
                if (before != null && loaded != null) loaded.privateDirtyPlusSwapKb - before.privateDirtyPlusSwapKb else null
            this["peakPrivateDirtyPlusSwapKb"] = run.memory.maxOfOrNull { it.privateDirtyPlusSwapKb }
            this["peakFileBackedKb"] = run.memory.mapNotNull { it.fileBackedKb }.maxOrNull()
        }
    }

    private fun clip(outcome: ClipOutcome, on: MeasuredOn): Map<String, Any?> {
        val ptt = outcome.ptt
        val result = ptt?.result
        val parsed = outcome.parsed
        return linkedMapOf<String, Any?>(
            "measuredOn" to on,
            "file" to outcome.clip.file,
            "id" to outcome.clip.id,
            "intent" to outcome.clip.intent,
            "text" to outcome.clip.text,
            "clipMs" to round(outcome.clip.seconds * 1000, 1),
        ).apply {
            if (ptt != null) {
                this["status"] = ptt.status
                this["transcript"] = result?.text
                this["alternatives"] = result?.alternatives?.map { mapOf("text" to it.text, "score" to it.score) }
                this["failure"] = result?.failure
                this["plan"] = (parsed as? ParseResult.Accepted)?.plan?.steps?.map {
                    linkedMapOf("name" to it.command.name, "arguments" to it.command.arguments)
                }
                this["rejection"] = (parsed as? ParseResult.Rejected)?.message
                this["verdict"] = outcome.verdict
                this["audioMs"] = round(ptt.audioMs, 1)
                this["pttUpToDrainedMs"] = ptt.pttUpToDrainedMs?.let { round(it, 1) }
                this["drainedToFinalMs"] = ptt.drainedToFinalMs?.let { round(it, 1) }
                this["pttUpToFinalMs"] = ptt.pttUpToFinalMs?.let { round(it, 1) }
                this["parseMs"] = ptt.parseMs?.let { round(it, 2) }
                this["pttUpToParsedMs"] = ptt.pttUpToParsedMs?.let { round(it, 1) }
                this["stopTokenAfterReleaseMs"] = ptt.stopTokenAfterReleaseMs?.let { round(it, 1) }
                this["acceptMs"] = round(ptt.acceptMs, 1)
                this["finishMs"] = round(ptt.finishMs, 1)
                this["rtf"] = ptt.rtf?.let { round(it, 4) }
            }
            outcome.spotter?.let { heard ->
                this["spotter"] = linkedMapOf(
                    "detections" to heard.detections,
                    "verdict" to outcome.spotterVerdict,
                    "firstAfterClipStartMs" to heard.firstAfterClipStartMs?.let { round(it, 1) },
                    "firstAfterReleaseMs" to heard.firstAfterReleaseMs?.let { round(it, 1) },
                    "fedMs" to round(heard.fedMs, 1),
                    "computeMs" to round(heard.computeMs, 1),
                    "rtf" to heard.rtf?.let { round(it, 4) },
                )
            }
        }
    }

    private fun memory(sample: MemorySample): Map<String, Any?> = linkedMapOf(
        "label" to sample.label,
        "privateDirtyKb" to sample.privateDirtyKb,
        "swapPssKb" to sample.swapPssKb,
        "privateDirtyPlusSwapKb" to sample.privateDirtyPlusSwapKb,
        "totalPssKb" to sample.totalPssKb,
        "fileBackedKb" to sample.fileBackedKb,
        "stats" to sample.stats,
        "smapsRollupKb" to sample.rollupKb,
    )

    private fun latency(values: List<Double>): Map<String, Any?>? = LatencyStats.summarize(values)?.let {
        linkedMapOf("n" to it.count, "p50Ms" to round(it.p50Ms, 1), "p95Ms" to round(it.p95Ms, 1), "maxMs" to round(it.maxMs, 1))
    }

    private fun ratio(values: List<Double>): Map<String, Any?>? = LatencyStats.summarize(values)?.let {
        linkedMapOf("n" to it.count, "p50" to round(it.p50Ms, 4), "p95" to round(it.p95Ms, 4), "max" to round(it.maxMs, 4))
    }

    private fun round(value: Double, decimals: Int): Double {
        if (!value.isFinite()) return value
        var scale = 1.0
        repeat(decimals) { scale *= 10 }
        return (value * scale).roundToLong() / scale
    }

    const val BEFORE_LOAD = "before_load"
    const val AFTER_LOAD = "after_load"
    const val AFTER_DECODE = "after_decode"
    private const val STOP = "stop"
}

/** Pretty JSON (two-space indent, insertion order) for the report; non-finite numbers become null. */
object BenchJson {
    fun write(value: Any?): String = StringBuilder().apply { value(value, "") }.append('\n').toString()

    private fun StringBuilder.value(value: Any?, indent: String) {
        when (value) {
            null -> append("null")
            is Boolean -> append(value)
            is Double -> if (value.isFinite()) append(value.toString()) else append("null")
            is Float -> if (value.isFinite()) append(value.toString()) else append("null")
            is Number -> append(value.toString())
            is Enum<*> -> string(value.name)
            is Map<*, *> -> block('{', '}', value.entries.toList(), indent) { (key, item), inner ->
                string(key.toString())
                append(": ")
                value(item, inner)
            }
            is Collection<*> -> block('[', ']', value.toList(), indent) { item, inner -> value(item, inner) }
            else -> string(value.toString())
        }
    }

    private fun <T> StringBuilder.block(open: Char, close: Char, items: List<T>, indent: String,
                                        each: StringBuilder.(T, String) -> Unit) {
        if (items.isEmpty()) {
            append(open).append(close)
            return
        }
        append(open).append('\n')
        val inner = "$indent  "
        items.forEachIndexed { i, item ->
            append(inner)
            each(item, inner)
            append(if (i < items.size - 1) ",\n" else "\n")
        }
        append(indent).append(close)
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
