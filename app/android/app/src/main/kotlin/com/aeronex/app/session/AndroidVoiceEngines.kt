package com.aeronex.app.session

import android.content.Context
import android.os.Process
import android.util.Log
import com.aeronex.app.LocalModelInventory
import com.aeronex.app.VoskModels
import com.aeronex.app.audio.AndroidPcmSource
import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.speech.AvailableEngines
import com.aeronex.app.speech.CommandEngineChoice
import com.aeronex.app.speech.CtcDecoder
import com.aeronex.app.speech.CtcModelSpec
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.GrammarAssets
import com.aeronex.app.speech.KwsDecoder
import com.aeronex.app.speech.KwsModelSpec
import com.aeronex.app.speech.LazyCtcEngine
import com.aeronex.app.speech.ModelPackStore
import com.aeronex.app.speech.PackEngine
import com.aeronex.app.speech.SherpaNativeCtc
import com.aeronex.app.speech.SherpaNativeKws
import com.aeronex.app.speech.SpeechEngines
import com.aeronex.app.speech.SpeechModelPack
import com.aeronex.app.speech.SpeechPackManifest
import com.aeronex.app.speech.StopChoice
import com.aeronex.app.speech.StopSpotter
import com.aeronex.app.speech.VoicePlan
import com.aeronex.app.speech.VoskModelHandle
import com.aeronex.app.speech.VoskNativeModel
import java.io.File

/**
 * The phone's [VoiceEngineSource] (T11 wiring, 0.4.0 loading): [EnginePolicy] picks the engines for the language from
 * what is installed and verified, then they are opened from the verified files and the shipped grammar assets. Runs on
 * the speech thread; a Vosk model is opened and closed there (its directory read lease belongs to that thread).
 * Hashing the installed models happens once per process (the inventories cache it).
 *
 * - Voice packs waiting in the import folder start importing on the [ModelPackStore] installer (single-flight, shared
 *   with the Models screen), never on the speech thread; only packs this phone can use are copied. Voice that needs one
 *   says [VoiceReason.INSTALLING_PACK], and the engines report [VoiceEngines.outdated] once a pack this language can
 *   use starts (voice off) or finishes (voice on, and only when it changes the [VoicePlan]) installing. A failed
 *   import is not retried in this process.
 * - IndicConformer opens as a [LazyCtcEngine]: its ~200 MB model loads at the first press or a pre-warm, not here.
 * - Tier H Telugu opens the Vosk-te model for its stop grammar only, beside the CTC command engine.
 */
class AndroidVoiceEngines internal constructor(
    policy: () -> EnginePolicy,
    grammar: () -> GrammarAssets,
    packs: () -> List<SpeechModelPack>,
    packStore: () -> ModelPackStore,
    private val voskReady: () -> Set<String>,
    private val voskDirectory: (String) -> File,
    private val kwsKeywordsFile: (SpeechModelPack) -> File,
    private val openVosk: (File) -> VoskModelHandle = ::VoskNativeModel,
    private val ctcDecoders: (CtcModelSpec) -> CtcDecoder = ::SherpaNativeCtc,
    private val kwsDecoders: (KwsModelSpec) -> KwsDecoder = ::SherpaNativeKws,
    private val ctcLoader: (Runnable) -> Unit = LazyCtcEngine::loaderThread,
    private val warn: (String) -> Unit = {},
) : VoiceEngineSource {
    constructor(context: Context) : this(
        policy = { EnginePolicy(DeviceFacts.read(context)) },
        grammar = { GrammarAssets.load(context.assets::open) },
        packs = { SpeechPackManifest.parse(context.assets.open(SpeechPackManifest.ASSET).bufferedReader().use { it.readText() }) },
        packStore = { ModelPackStore.forContext(context) },
        voskReady = readyVoskModels(File(context.filesDir, "models")),
        voskDirectory = { File(File(context.filesDir, "models"), VoskModels.profiles.getValue(it).runtimeFile) },
        kwsKeywordsFile = { File(context.noBackupFilesDir, "kws/keywords-${it.id}.txt") },
        warn = { Log.w(TAG, it) },
    )

    val policy: EnginePolicy by lazy(policy)
    private val grammar by lazy(grammar)
    private val packs by lazy(packs)
    private val packStore by lazy(packStore)

    override fun open(language: String): VoiceEngines {
        val relevant = relevantPacks(language)
        startWaitingImports(relevant)
        val plan = plan(language)
        return when (val command = plan.command) {
            null -> {
                val installing = relevant.any { packStore.installing(it) != null }
                val reason = if (installing) VoiceReason.INSTALLING_PACK else VoiceEngines.reasonFor(plan) ?: VoiceReason.NOT_INSTALLED
                VoiceEngines.none(language, reason, plan, outdated = watch(relevant, null))
            }
            is CommandEngineChoice.Vosk -> vosk(language, command.profileId, plan, watch(relevant, plan))
            is CommandEngineChoice.Ctc -> ctc(language, command, plan, watch(relevant, plan))
        }
    }

    /** The engines for [language] from what is installed and verified now (the hashes are cached per process). */
    private fun plan(language: String) = policy.plan(language, AvailableEngines(voskReady(), packStore.readyPacks(packs)))

    /** The packs that can change [language]'s engines on this phone: its CTC pack, and the stop spotters for it. */
    private fun relevantPacks(language: String): List<SpeechModelPack> {
        val usable = packs.filter { policy.packReason(it) == null }
        val ctc = usable.filter { it.engine == PackEngine.NEMO_CTC && it.language == language }
        return if (ctc.isEmpty()) emptyList() else ctc + usable.filter { it.engine == PackEngine.KWS }
    }

    /** Packs pushed into the import folder start importing now; one whose import failed is left for the operator. */
    private fun startWaitingImports(relevant: List<SpeechModelPack>) {
        val waiting = relevant.filterNot { pack ->
            packStore.lastInstall(pack)?.let { it.finished && it.source == ModelPackStore.Install.Source.IMPORT && !it.installed } == true
        }
        for (install in packStore.startWaitingImports(waiting)) {
            install.whenFinished { done ->
                done.error?.let { warn("Speech pack ${done.pack.id} was not installed: ${it.message}") }
            }
        }
    }

    /**
     * For voice-off engines ([opened] null): true once a [relevant] pack starts or ends installing. For voice engines:
     * true once one finished installing and the plan is no longer [opened] (on Tier H Telugu the KWS changes nothing,
     * so the CTC model stays loaded). The session then reopens. Polled on the speech thread (voice engines only on the
     * ground, never in flight): a few map lookups, and the plan only when an install finished (its files were hashed on
     * the installer).
     */
    private fun watch(relevant: List<SpeechModelPack>, opened: VoicePlan?): () -> Boolean {
        val language = opened?.language
        fun signature(): Long = relevant.fold(0L) { sum, pack ->
            31 * sum + 2 * packStore.completions(pack) + if (language == null && packStore.installing(pack) != null) 1 else 0
        }
        var checked = signature()
        var outdated = false
        return {
            val now = signature()
            if (now != checked) {
                checked = now
                // A plan that cannot be worked out keeps the working engines.
                outdated = language == null || runCatching { plan(language) != opened }.getOrDefault(false)
            }
            outdated
        }
    }

    private fun vosk(language: String, profileId: String, plan: VoicePlan, outdated: () -> Boolean): VoiceEngines {
        var tap: PartialTap? = null
        val engine = SpeechEngines.vosk(language, voskDirectory(profileId), grammar, load = { dir ->
            PartialTap(openVosk(dir), grammar.voskGrammar(language)).also { tap = it }
        })
        val partial = tap
        // The engine closes its own stop recognizer and then the model.
        return VoiceEngines(language, engine, engine.stopSpotter, plan = plan, partial = { partial?.text.orEmpty() },
            resources = listOf(engine), outdated = outdated)
    }

    private fun ctc(language: String, command: CommandEngineChoice.Ctc, plan: VoicePlan, outdated: () -> Boolean): VoiceEngines {
        // A new engine per language: closing it on a language change frees its ~300 MiB model before the next load.
        val spec = CtcModelSpec.of(command.pack, checkNotNull(packStore.readyFiles(command.pack)), plan.asrThreads)
        val ctc = LazyCtcEngine(spec, ctcDecoders, ctcLoader)
        try {
            val spotter: StopSpotter? = when (val choice = plan.spotter) {
                is StopChoice.Kws -> SpeechEngines.kws(choice.pack, checkNotNull(packStore.readyFiles(choice.pack)), grammar,
                    language, kwsKeywordsFile(choice.pack).also { it.parentFile?.mkdirs() }, numThreads = policy.kwsThreads,
                    load = kwsDecoders)
                // Tier H Telugu: the stop grammar on the language's own Vosk model (native voice STOP).
                StopChoice.VoskStopGrammar -> SpeechEngines.voskStop(language,
                    voskDirectory(checkNotNull(VoskModels.profileForLanguage(language))), grammar, openVosk)
                null -> null
            }
            return VoiceEngines(language, ctc, spotter, plan = plan, outdated = outdated)
        } catch (error: Throwable) {
            ctc.close()
            throw error
        }
    }

    companion object {
        private const val TAG = "AeroNexVoice"

        /** The verified Vosk model profile ids in [models]; the inventory is created at the first voice start. */
        private fun readyVoskModels(models: File): () -> Set<String> {
            val inventory by lazy { LocalModelInventory(models, VoskModels.profiles) }
            return { inventory.states().filterValues { it == "ready" }.keys }
        }

        /** The session's microphone: AudioRecord VOICE_RECOGNITION on a URGENT_AUDIO thread (spec 4.2). */
        fun capture(listener: AudioCapture.Listener) =
            AudioCapture(AndroidPcmSource(), listener = listener, threadSetup = AndroidPcmSource::urgentAudioPriority)

        /**
         * The decode thread: above background work but below UI rendering, the link threads (URGENT_DISPLAY) and
         * capture, so a heavy decode never delays the heartbeat or a STOP frame (spec 4.1, 4.11).
         */
        fun speechThreadSetup() = Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
    }
}
