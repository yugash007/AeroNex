package com.aeronex.app.bench

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.Process
import com.aeronex.app.LocalModelInventory
import com.aeronex.app.VerifiedModelStore
import com.aeronex.app.VoskModels
import com.aeronex.app.audio.AndroidPcmSource
import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.grammar.GrammarTables
import com.aeronex.app.speech.AvailableEngines
import com.aeronex.app.speech.CommandEngineChoice
import com.aeronex.app.speech.CtcModelSpec
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.GrammarAssets
import com.aeronex.app.speech.ModelPackStore
import com.aeronex.app.speech.PackEngine
import com.aeronex.app.speech.SpeechEngine
import com.aeronex.app.speech.SpeechEngines
import com.aeronex.app.speech.SpeechModelPack
import com.aeronex.app.speech.SpeechPackManifest
import com.aeronex.app.speech.StopChoice
import com.aeronex.app.speech.StopSpotter
import com.aeronex.app.speech.VoskModelHandle
import com.aeronex.app.speech.VoskNativeModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One bench run on the bench thread (Android side of [BenchActivity]): import pushed packs, then for each
 * [BenchPlan] unit sample memory, load the engine (timed), sample memory, run its clips through [ClipBench] at real
 * time, sample memory, run the probes, close it. The engines are opened exactly as the session opens them
 * ([SpeechEngines], verified files, shipped grammar assets); CTC threads follow [EnginePolicy] unless the request
 * overrides them. Everything is written to `bench/<timestamp>.json`, an error included.
 */
internal class BenchRun(
    private val context: Context,
    private val request: BenchRequest,
    private val show: (String) -> Unit,
) {
    private val files = checkNotNull(context.getExternalFilesDir(null)) { "No app-specific external storage" }
    private val benchDir = File(files, "bench")
    private val clipsDir = File(benchDir, "clips")
    private val importDir = File(files, "import")
    private val models = File(context.filesDir, "models")
    private val facts = DeviceFacts.read(context)
    private val policy = EnginePolicy(facts)
    private val ctcThreads = request.threads ?: policy.asrThreads
    private val measuredOn = BenchEnvironment.measuredOn(Build.FINGERPRINT, Build.HARDWARE, Build.PRODUCT, Build.MODEL)

    private class Opened(
        val name: String,
        val threads: Int?,
        val engine: SpeechEngine?,
        val spotter: StopSpotter?,
        val resource: AutoCloseable,
        val probe: (ShortArray) -> Map<String, Any?> = { emptyMap() },
    )

    fun run() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
        } catch (_: Exception) {
            // Default priority rather than no bench.
        }
        val startedAt = now()
        show("AeroNex bench ($measuredOn): ${request.kinds.map { it.wire }} ${request.languages}, ${request.clipsPerLanguage} clips")
        val install = mutableListOf<Map<String, Any?>>()
        val runs = mutableListOf<EngineRun>()
        val environment = environment()
        var plan = BenchPlan(emptyList(), emptyMap())
        var baseline: MemorySample? = null
        var error: String? = null
        try {
            baseline = sample("baseline")
            val grammar = GrammarAssets.load(context.assets::open)
            val packs = context.assets.open(SpeechPackManifest.ASSET).bufferedReader().use { SpeechPackManifest.parse(it.readText()) }
            val store = ModelPackStore.forContext(context)
            val installed = install(packs, store, install)
            environment["policy"] = policyChoices(installed, packs)
            plan = BenchPlan.of(request, Catalogue(
                packs.filter { it.engine == PackEngine.NEMO_CTC }.associate { it.language to it.id },
                packs.filter { it.engine == PackEngine.KWS }.map { it.id to it.language }), installed)
            plan.skipped.forEach { (id, why) -> show("skip $id: $why") }
            val index = ClipIndex.parse(File(clipsDir, ClipIndex.FILE).readText())
            for (unit in plan.units) runs += runUnit(unit, grammar, packs, store, index)
        } catch (failure: Throwable) {
            error = describe(failure)
            show("FAILED: $error")
        }
        val report = BenchReport.build(measuredOn, environment, settings(), install, plan.skipped, baseline, runs,
            startedAt, now(), error)
        benchDir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val part = File(benchDir, "$name.json.part")
        part.writeText(BenchJson.write(report))
        val done = File(benchDir, "$name.json")
        check(part.renameTo(done)) { "Could not publish ${done.path}" }
        show("Wrote ${done.path}")
    }

    /** Imports what the request can use from the import folder (hashing a 200 MB pack takes seconds). */
    private fun install(packs: List<SpeechModelPack>, store: ModelPackStore, log: MutableList<Map<String, Any?>>): Installed {
        val ready = mutableSetOf<String>()
        val wanted = packs.filter {
            it.language in request.languages && when (it.engine) {
                PackEngine.NEMO_CTC -> UnitKind.CTC in request.kinds
                PackEngine.KWS -> UnitKind.KWS in request.kinds
            }
        }
        for (pack in wanted) {
            val before = store.state(pack)
            var importMs: Double? = null
            var failure: String? = null
            if (before != ModelPackStore.State.READY && store.importAvailable(pack)) {
                show("importing ${pack.id} ...")
                val started = System.nanoTime()
                try {
                    store.import(pack, { false }, {})
                } catch (error: Exception) {
                    failure = describe(error)
                }
                importMs = (System.nanoTime() - started) / 1e6
            }
            val after = store.state(pack)
            if (after == ModelPackStore.State.READY) ready += pack.id
            log += linkedMapOf("id" to pack.id, "before" to before, "after" to after, "importMs" to importMs, "error" to failure)
        }
        val vosk = mutableSetOf<String>()
        if (UnitKind.VOSK in request.kinds) {
            for (code in request.languages.filter { it in GrammarTables.VOSK_LANGUAGES }) {
                val id = checkNotNull(VoskModels.profileForLanguage(code))
                val profile = VoskModels.profiles.getValue(id)
                val before = LocalModelInventory(models, mapOf(id to profile)).states().getValue(id)
                val zip = File(importDir, "$id/${profile.file}")
                var importMs: Double? = null
                var failure: String? = null
                if (before != "ready" && zip.isFile) {
                    show("importing $id ...")
                    val started = System.nanoTime()
                    try {
                        zip.inputStream().use { VerifiedModelStore(models).install(profile, it, { false }) }
                    } catch (error: Exception) {
                        failure = describe(error)
                    }
                    importMs = (System.nanoTime() - started) / 1e6
                }
                val after = LocalModelInventory(models, mapOf(id to profile)).states().getValue(id)
                if (after == "ready") vosk += code
                log += linkedMapOf("id" to id, "before" to before, "after" to after, "importMs" to importMs, "error" to failure)
            }
        }
        return Installed(vosk, ready)
    }

    private fun runUnit(unit: BenchUnit, grammar: GrammarAssets, packs: List<SpeechModelPack>, store: ModelPackStore,
                        index: List<ClipInfo>): EngineRun {
        val clips = ClipIndex.select(index, unit.language, request.clipsPerLanguage)
            .map { BenchClip(it, WavPcm.read(File(clipsDir, it.file).readBytes())) }
        check(clips.isNotEmpty()) { "No ${unit.language} clips in ${clipsDir.path}" }
        show("== ${unit.id}: loading")
        val memory = mutableListOf(sample(BenchReport.BEFORE_LOAD))
        val started = System.nanoTime()
        val opened = try {
            open(unit, grammar, packs, store)
        } catch (failure: Throwable) {
            show("${unit.id} failed to load: ${describe(failure)}")
            return EngineRun(unit, unit.kind.wire, null, null, memory, emptyList(), error = describe(failure))
        }
        val loadMs = (System.nanoTime() - started) / 1e6
        memory += sample(BenchReport.AFTER_LOAD)
        show("${unit.id}: ${opened.name} loaded in ${"%.0f".format(loadMs)} ms")
        val outcomes = mutableListOf<ClipOutcome>()
        var error: String? = null
        var probes: Map<String, Any?> = emptyMap()
        try {
            val player = ClipPlayer()
            val capture = AudioCapture(player, threadSetup = AndroidPcmSource::urgentAudioPriority)
            capture.start()
            try {
                val bench = ClipBench(capture.ring, player, opened.engine?.let(::TimedEngine), opened.spotter?.let(::TimedSpotter))
                for (clip in clips) outcomes += bench.run(clip).also { show(line(unit, it)) }
            } finally {
                capture.stop()
            }
            memory += sample(BenchReport.AFTER_DECODE)
            probes = opened.probe(clips.first().samples)
        } catch (failure: Throwable) {
            error = describe(failure)
            show("${unit.id} failed: $error")
        } finally {
            try {
                opened.resource.close()
            } catch (failure: Throwable) {
                if (error == null) error = "close: ${describe(failure)}"
            }
        }
        return EngineRun(unit, opened.name, opened.threads, loadMs, memory, outcomes, probes, error)
    }

    /** Opens the unit's engine as the session does (AndroidVoiceEngines), from the verified files. */
    private fun open(unit: BenchUnit, grammar: GrammarAssets, packs: List<SpeechModelPack>, store: ModelPackStore): Opened =
        when (unit.kind) {
            UnitKind.VOSK -> {
                val id = checkNotNull(VoskModels.profileForLanguage(unit.language))
                val directory = File(models, VoskModels.profiles.getValue(id).runtimeFile)
                var model: VoskModelHandle? = null
                val engine = SpeechEngines.vosk(unit.language, directory, grammar, load = { VoskNativeModel(it).also { m -> model = m } })
                val command = grammar.voskGrammar(unit.language)
                Opened("VOSK_GRAMMAR $id (vosk-android 0.3.75)", 1, engine, engine.stopSpotter, engine, probe = { samples ->
                    linkedMapOf("voskPartialWords" to VoskPartialProbe.run(checkNotNull(model), command, samples),
                        "voskRecognizerSetters" to voskSetters())
                })
            }
            UnitKind.CTC -> {
                val pack = packs.single { it.id == unit.packId }
                val ctc = SpeechEngines.ctc()
                try {
                    ctc.load(CtcModelSpec.of(pack, checkNotNull(store.readyFiles(pack)), ctcThreads))
                } catch (failure: Throwable) {
                    ctc.close()
                    throw failure
                }
                Opened("SHERPA_CTC ${pack.id} (sherpa-onnx 1.13.8)", ctcThreads, ctc, null, ctc)
            }
            UnitKind.KWS -> {
                val pack = packs.single { it.id == unit.packId }
                val keywords = File(context.noBackupFilesDir, "kws/bench-keywords-${pack.id}.txt").also { it.parentFile?.mkdirs() }
                val kws = SpeechEngines.kws(pack, checkNotNull(store.readyFiles(pack)), grammar, unit.language, keywords,
                    numThreads = policy.kwsThreads)
                Opened("SHERPA_KWS ${pack.id} (sherpa-onnx 1.13.8)", policy.kwsThreads, null, kws, kws)
            }
        }

    /** Which recognizer setters vosk-android exposes (T11: is setEndpointerDelays there?). */
    private fun voskSetters(): List<String> =
        org.vosk.Recognizer::class.java.methods.map { it.name }.filter { it.startsWith("set") }.distinct().sorted()

    private fun sample(label: String): MemorySample {
        val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val stats = info.memoryStats.mapNotNull { (key, value) -> value.toLongOrNull()?.let { key to it } }.toMap()
        val rollup = try {
            SmapsRollup.parse(File(SmapsRollup.PATH).readText()).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
        return MemorySample(label, info.totalPrivateDirty.toLong(), stats["summary.total-swap"], info.totalPss.toLong(),
            stats, rollup)
    }

    private fun environment(): MutableMap<String, Any?> {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "product" to Build.PRODUCT,
            "hardware" to Build.HARDWARE,
            "fingerprint" to Build.FINGERPRINT,
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
            "processArch" to System.getProperty("os.arch"),
            "totalMemBytes" to facts.totalMemBytes,
            "lowRamDevice" to facts.lowRamDevice,
            "cpuCores" to facts.cpuCores,
            "tier" to policy.tier,
            "asrThreads" to policy.asrThreads,
            "app" to linkedMapOf(
                "package" to context.packageName,
                "versionName" to packageInfo.versionName,
                "versionCode" to versionCode,
                "debuggable" to (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0),
            ),
        )
    }

    /** What the session's EnginePolicy would pick on this device with what is installed now. */
    private fun policyChoices(installed: Installed, packs: List<SpeechModelPack>): Map<String, Any?> {
        val voskIds = installed.voskLanguages.mapNotNull(VoskModels::profileForLanguage).toSet()
        val available = AvailableEngines(voskIds, packs.filter { it.id in installed.packs })
        return request.languages.associateWith { code ->
            val plan = policy.plan(code, available)
            val command = when (val choice = plan.command) {
                is CommandEngineChoice.Vosk -> "vosk:${choice.profileId}"
                is CommandEngineChoice.Ctc -> "ctc:${choice.pack.id}"
                null -> null
            }
            val spotter = when (val choice = plan.spotter) {
                StopChoice.VoskStopGrammar -> "vosk-stop-grammar"
                is StopChoice.Kws -> "kws:${choice.pack.id}"
                null -> null
            }
            linkedMapOf("command" to command, "spotter" to spotter, "reason" to plan.reason)
        }
    }

    private fun settings(): Map<String, Any?> = linkedMapOf(
        "engines" to request.kinds.map { it.wire },
        "languages" to request.languages,
        "clipsPerLanguage" to request.clipsPerLanguage,
        "ctcThreads" to ctcThreads,
        "feed" to "real time: one 20 ms frame per 20 ms through AudioCapture (EnergyVad) into the 3 s AudioRing",
        "ptt" to "PttUtterance: press at the clip start (500 ms pre-roll), release at the clip's last frame, " +
            "adaptive tail 60 ms non-speech / 250 ms max",
        "spotter" to "SpotterGate armed for the whole clip (the in-flight case), fed on the speech thread",
        "settleMs" to 800,
        "clips" to "eSpeak NG 1.52 TTS (T3 clips-espeak), 16 kHz mono, 200 ms silence padding at both ends",
    )

    private fun line(unit: BenchUnit, outcome: ClipOutcome): String = buildString {
        append(unit.id).append(' ').append(outcome.clip.file.substringAfterLast('/')).append(':')
        outcome.ptt?.let { ptt ->
            append(' ').append(ptt.status).append(" '").append(ptt.result?.text.orEmpty()).append("' ").append(outcome.verdict)
            ptt.pttUpToFinalMs?.let { append(" up->final ").append("%.0f".format(it)).append(" ms") }
            ptt.rtf?.let { append(" rtf ").append("%.3f".format(it)) }
        }
        outcome.spotter?.let { append(" spotter ").append(outcome.spotterVerdict).append(" x").append(it.detections) }
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun describe(error: Throwable) = "${error.javaClass.simpleName}: ${error.message}"
}
