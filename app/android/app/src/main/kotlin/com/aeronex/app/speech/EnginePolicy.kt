package com.aeronex.app.speech

import com.aeronex.app.VoskModels
import com.aeronex.app.grammar.GrammarTables

/** The command recognizer chosen for a session. */
sealed interface CommandEngineChoice {
    val runtime: SpeechRuntime

    /** A resident Vosk model ([VoskModels] profile id) with the language's command and stop grammars. */
    data class Vosk(val profileId: String) : CommandEngineChoice {
        override val runtime get() = SpeechRuntime.VOSK
    }

    data class Ctc(val pack: SpeechModelPack) : CommandEngineChoice {
        override val runtime get() = SpeechRuntime.SHERPA_ONNX
    }
}

/**
 * The voice stop spotter chosen for a session: the command runtime's own, except on Tier H Telugu (0.4.0), where the
 * Vosk-te model stays resident for its stop grammar beside the IndicConformer command engine.
 */
sealed interface StopChoice {
    val runtime: SpeechRuntime

    /** The stop-only grammar on the language's resident Vosk model: the language's own stop words. */
    data object VoskStopGrammar : StopChoice {
        override val runtime get() = SpeechRuntime.VOSK
    }

    /** An English keyword spotter ("stop" only until Phase 2.5). */
    data class Kws(val pack: SpeechModelPack) : StopChoice {
        override val runtime get() = SpeechRuntime.SHERPA_ONNX
    }
}

/** What is installed and verified: ready Vosk model profile ids and ready sherpa packs. */
data class AvailableEngines(val voskModels: Set<String>, val packs: List<SpeechModelPack>)

/**
 * The voice set-up for one language on this phone. [command] null means the "No voice" level (4.11): tap pad, typed
 * commands and the physical STOP lanes only; [reason] says why. [spotter] null means no voice STOP (physical lanes
 * still work).
 */
data class VoicePlan(
    val language: String,
    val tier: DeviceTier,
    val command: CommandEngineChoice?,
    val spotter: StopChoice?,
    val asrThreads: Int,
    val reason: String?,
) {
    val runtimes: Set<SpeechRuntime> = setOfNotNull(command?.runtime, spotter?.runtime)
    val voice: Boolean get() = command != null

    /** Voice STOP in the operator's language (Vosk stop grammar); the KWS knows English "stop" only. */
    val nativeVoiceStop: Boolean get() = spotter == StopChoice.VoskStopGrammar

    /** Stop tokens in streaming partials while PTT is held (spec 4.12 trigger 5): streaming engines only. */
    val partialStop: Boolean get() = command is CommandEngineChoice.Vosk

    init {
        // Spec 4.0/6.2: a hard rule on Tier L and M; Tier H Telugu adds the Vosk-te stop spotter to CTC (0.4.0).
        check(runtimes.size <= 1 || tier == DeviceTier.H) { "One ML runtime per session on Tier $tier, got $runtimes" }
    }
}

/**
 * Device tier and engine selection (spec 6.1, 6.2, 4.6). Tier: 32-bit-only userspace or totalMem below 3.3 GiB is L,
 * below 5.5 GiB M, else H; a missed first-session benchmark downgrades one tier. The Go flag selects the strict
 * memory profile (5 Hz snapshots, no hands-free, one resident pack); it never sets the tier.
 *
 * Engines per language (Phase 1b defaults, Telugu as of 0.4.0):
 * - en, hi: the Vosk grammar engine on every tier (no CTC pack exists for them).
 * - te: Vosk-te only on Tier L (streaming, native voice STOP). On Tier M/H IndicConformer-te CTC when installed: on
 *   Tier H with Vosk-te kept resident as the streaming stop spotter (native Telugu voice STOP, the one two-runtime
 *   session), on Tier M (and on Tier H without Vosk-te or with the strict profile) with the English KWS. When CTC
 *   would have no voice spotter at all, Vosk-te stays the command engine (safer: it keeps voice STOP).
 * - ta, kn, ml: IndicConformer CTC on Tier M/H only (W1: about 300 MiB heap per language). Tier L only after a
 *   first-session benchmark that met RTF <= 0.15 and the memory cap (spec 6.3 gate).
 * Otherwise the stop spotter uses the command engine's runtime: Vosk stop grammar, or the KWS pack (gigaspeech by
 * default; an optional pack such as the zh-en model, whose licence is unconfirmed, only when listed in
 * [optionalPacks]). Thread counts: ASR 2 on Tier L (tuned 2-4), 4 on M/H, never more than the cores; KWS 1.
 * Link and STOP threads are not this policy's business and are never throttled.
 */
class EnginePolicy(
    val facts: DeviceFacts,
    val benchmark: FirstSessionBenchmark? = null,
    private val optionalPacks: Set<String> = emptySet(),
) {
    val classifiedTier: DeviceTier = when {
        facts.supported64BitAbis.isEmpty() -> DeviceTier.L
        facts.totalMemBytes < TIER_M_TOTAL_MEM_BYTES -> DeviceTier.L
        facts.totalMemBytes < TIER_H_TOTAL_MEM_BYTES -> DeviceTier.M
        else -> DeviceTier.H
    }

    val tier: DeviceTier = if (benchmark?.missedTierTargets == true) classifiedTier.downgrade() else classifiedTier

    /** The Go flag: a lower memory cap within the tier (spec 6.1). */
    val strictMemoryProfile: Boolean = facts.lowRamDevice

    /** EventChannel snapshot rate (spec 4.1, 6.2): 5 Hz on Tier L or the strict profile, else 10 Hz. */
    val snapshotHz: Int = if (tier == DeviceTier.L || strictMemoryProfile) 5 else 10
    val snapshotPeriodMs: Long get() = 1000L / snapshotHz

    val handsFreeAllowed: Boolean = tier != DeviceTier.L && !strictMemoryProfile
    val residentLanguagePacks: Int = if (tier == DeviceTier.H && !strictMemoryProfile) 2 else 1

    val asrThreads: Int = minOf(
        if (tier == DeviceTier.L) (benchmark?.tunedAsrThreads ?: TIER_L_THREADS).coerceIn(TIER_L_THREADS, 4) else 4,
        facts.cpuCores,
    ).coerceAtLeast(1)

    val kwsThreads: Int = 1

    /** Spec 6.3 Tier L non-streaming gate, as far as the first-session benchmark measures it. */
    val tierLCtcGatePassed: Boolean = benchmark?.let {
        it.ctcRtf != null && it.ctcRtf <= TIER_L_CTC_MAX_RTF && it.ctcWithinMemoryCap
    } == true

    /** Tier H keeps a language's Vosk model resident as the stop spotter beside its CTC engine (0.4.0: Telugu). */
    private val voskStopBesideCtc: Boolean = tier == DeviceTier.H && !strictMemoryProfile

    fun plan(language: String, available: AvailableEngines): VoicePlan {
        if (language !in GrammarTables.LANGUAGES) return VoicePlan(language, tier, null, null, asrThreads, UNSUPPORTED)
        val vosk = VoskModels.profileForLanguage(language)?.takeIf { it in available.voskModels }
        val ctcPack = available.packs.firstOrNull {
            it.engine == PackEngine.NEMO_CTC && it.language == language && packReason(it) == null
        }
        val kws = available.packs.filter { it.engine == PackEngine.KWS && usable(it) }
            .sortedByDescending { it.defaultEnabled }.firstOrNull()
        val command = when {
            ctcPack == null -> vosk?.let { CommandEngineChoice.Vosk(it) }
            vosk == null || voskStopBesideCtc || kws != null -> CommandEngineChoice.Ctc(ctcPack)
            else -> CommandEngineChoice.Vosk(vosk)
        }
        val spotter = when (command) {
            is CommandEngineChoice.Vosk -> StopChoice.VoskStopGrammar
            is CommandEngineChoice.Ctc ->
                if (vosk != null && voskStopBesideCtc) StopChoice.VoskStopGrammar else kws?.let { StopChoice.Kws(it) }
            null -> null
        }
        val reason = when {
            command != null -> null
            // Without a Vosk grammar the only voice path is CTC, closed on Tier L: installing a pack would not help.
            language !in GrammarTables.VOSK_LANGUAGES && tier == DeviceTier.L && !tierLCtcGatePassed -> NEEDS_TIER_M
            else -> NOT_INSTALLED
        }
        return VoicePlan(language, tier, command, spotter, asrThreads, reason)
    }

    private fun usable(pack: SpeechModelPack) =
        (pack.defaultEnabled && pack.licenceConfirmed) || pack.id in optionalPacks

    /** Whether the Models screen offers [pack]: its licence is confirmed and it is on by default, or opted in. */
    fun listed(pack: SpeechModelPack): Boolean = usable(pack)

    /** Why [pack] cannot run on this phone (the Models screen's "Not for this phone"), or null when it can. */
    fun packReason(pack: SpeechModelPack): String? = when {
        !usable(pack) -> PACK_OFF
        pack.engine == PackEngine.NEMO_CTC && pack.language == "te" && tier == DeviceTier.L -> PACK_TELUGU_USES_VOSK
        pack.engine == PackEngine.NEMO_CTC && tier < pack.minTier && !tierLCtcGatePassed -> PACK_NEEDS_TIER_M
        // The KWS pairs with CTC only; on Tier L every voice language uses its own Vosk stop grammar.
        pack.engine == PackEngine.KWS && tier == DeviceTier.L && !tierLCtcGatePassed -> PACK_STOP_NOT_USED
        else -> null
    }

    /** Which voice STOP [pack] gives on this phone, for the Models screen; null when the pack cannot run here. */
    fun packNote(pack: SpeechModelPack): String? = when {
        packReason(pack) != null -> null
        pack.engine == PackEngine.KWS -> NOTE_STOP_PACK
        pack.language == "te" && voskStopBesideCtc -> NOTE_TELUGU_STOP
        else -> NOTE_ENGLISH_STOP
    }

    companion object {
        private const val GIB = 1L shl 30
        /** Spec 6.1 cut points (proposed; calibrated on the reference phones). */
        val TIER_M_TOTAL_MEM_BYTES: Long = (3.3 * GIB).toLong()
        val TIER_H_TOTAL_MEM_BYTES: Long = (5.5 * GIB).toLong()
        const val TIER_L_THREADS = 2
        const val TIER_L_CTC_MAX_RTF = 0.15

        const val UNSUPPORTED = "Voice commands are not available in this language."
        const val NOT_INSTALLED = "No speech model for this language is installed. Use the tap pad or typed commands."
        const val NEEDS_TIER_M = "Voice commands in this language need a phone with 4 GB of memory or more. " +
            "Use the tap pad or typed commands."

        // The Models screen (0.4.0 in-app voice packs).
        const val PACK_OFF = "Not offered: its licence is not confirmed."
        const val PACK_NEEDS_TIER_M = "Needs a 64-bit phone with 4 GB of memory or more."
        const val PACK_TELUGU_USES_VOSK = "On this phone Telugu uses the smaller Vosk Telugu pack."
        const val PACK_STOP_NOT_USED = "Not used on this phone: voice STOP uses each language's own pack."
        const val NOTE_ENGLISH_STOP = "With this pack, voice STOP is the English word \"stop\" (the Voice STOP pack)."
        const val NOTE_TELUGU_STOP = "Telugu voice STOP keeps using the Vosk Telugu pack: keep it installed."
        const val NOTE_STOP_PACK =
            "Lets you say \"stop\" in English to stop the drone while an IndicConformer pack is in use."
    }
}
