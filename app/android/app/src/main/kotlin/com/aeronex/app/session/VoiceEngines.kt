package com.aeronex.app.session

import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.command.model.FlightPlan
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.SpeechEngine
import com.aeronex.app.speech.StopChoice
import com.aeronex.app.speech.StopSpotter
import com.aeronex.app.speech.VoicePlan

/**
 * The engines one language's [VoicePlan] opened (T11): the command recognizer and the voice stop spotter of the same
 * runtime, or neither with the [reason]. Opened and [close]d on the speech thread (a Vosk model holds its directory's
 * read lease on that thread). [partial] is the live partial transcript of the utterance in progress, "" when the
 * engine has none. [resources] are closed in reverse order; the Vosk engine closes its own stop recognizer.
 * [outdated] turns true when opening the language again would give something else (a voice pack started or finished
 * installing); the session then reopens at a quiet moment (voice engines: asked, and reopened, only with the spotter
 * disarmed and [VoiceSink.onTheGround] true). Polled on the speech thread only: keep it cheap.
 */
class VoiceEngines(
    val language: String,
    val command: SpeechEngine?,
    val spotter: StopSpotter?,
    val reason: VoiceReason? = null,
    val plan: VoicePlan? = null,
    val partial: () -> String = { "" },
    private val resources: List<AutoCloseable> = listOfNotNull(command, spotter),
    val outdated: () -> Boolean = { false },
) : AutoCloseable {
    val spotterKind: SpotterKind? get() = when {
        spotter == null -> null
        plan?.spotter is StopChoice.Kws -> SpotterKind.KWS
        else -> SpotterKind.VOSK_STOP_GRAMMAR
    }

    override fun close() {
        var failure: Throwable? = null
        for (resource in resources.asReversed()) {
            try {
                resource.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    companion object {
        fun none(language: String, reason: VoiceReason, plan: VoicePlan? = null, outdated: () -> Boolean = { false }) =
            VoiceEngines(language, null, null, reason, plan, outdated = outdated)

        /** The operator reason for a plan without a command engine (spec 4.6, 4.11). */
        fun reasonFor(plan: VoicePlan): VoiceReason? = when {
            plan.command != null -> null
            plan.reason == EnginePolicy.NEEDS_TIER_M -> VoiceReason.NEEDS_NEWER_PHONE
            plan.reason == EnginePolicy.UNSUPPORTED -> VoiceReason.UNSUPPORTED
            else -> VoiceReason.NOT_INSTALLED
        }
    }
}

/** Opens a language's engines; runs on the speech thread. A missing model is a [VoiceEngines.reason], not a throw. */
fun interface VoiceEngineSource {
    fun open(language: String): VoiceEngines
}

/** What the speech thread hands to the session ([SessionController] implements it). */
interface VoiceSink {
    /** Changes with every STOP (any trigger): a plan from an utterance pressed before the latest STOP is never shown. */
    val stops: Long

    /**
     * Validates and shows a recognised [plan] with the Phone source, unless a STOP came after [stopsAtPress]; the
     * [SessionSchema.submitted] answer. Called on the speech thread.
     */
    fun submit(plan: FlightPlan, transcript: String, traceId: Long, stopsAtPress: Long): Map<String, Any?>

    /**
     * A transcript the parser rejected with [rulesMessage] (0.4.0): what the speech status shows as its error, or null
     * when the session's AI path took the utterance (its answer then arrives in the snapshot's `ai` section). Must not
     * block: called on the speech thread. Default: no AI, the rules' message.
     */
    fun notUnderstood(transcript: String, rulesMessage: String, stopsAtPress: Long): String? = rulesMessage

    /** A voice STOP (spotter or partial token): only posts to the supervisor. */
    fun voiceStop(trigger: StopTrigger)

    /** Spec 4.12: the spotter listens while the vehicle is armed or airborne and the app owns the mode. */
    fun spotterArmed(): Boolean

    /**
     * The vehicle is known to be on the ground: link up, disarmed, landed (0.4.0). This is the only way a newly installed
     * voice pack replaces voice engines while voice runs, since the pack can change the voice STOP word, and a disarmed
     * spotter alone may mean an RC flight in EXTERNAL mode or a link loss in the air. Default: not known, so a new pack
     * waits for the next voice start or language change. Polled every frame on the speech thread: keep it cheap.
     */
    fun onTheGround(): Boolean = false

    /** The speech status changed (poke the snapshot pump). Quick, any thread. */
    fun changed()
}

/**
 * Speech for a [SessionController]: [capture] builds a new [AudioCapture] (with the session's listener) each time voice
 * starts; [engines] opens a language's engines; [language] is the saved selection and [saveLanguage] keeps a new one;
 * [speechThreadSetup] runs first on the speech thread.
 */
class VoiceConfig(
    val capture: (AudioCapture.Listener) -> AudioCapture,
    val engines: VoiceEngineSource,
    val language: String = DEFAULT_LANGUAGE,
    val saveLanguage: (String) -> Unit = {},
    val speechThreadSetup: () -> Unit = {},
) {
    companion object {
        /** v0.2.0's default speech language. */
        const val DEFAULT_LANGUAGE = "te"
    }
}

/** Trace point names of the voice path (spec 5); the voice ids start at [VoiceSession.TRACE_ID_BASE]. */
object VoiceTrace {
    const val PTT_DOWN = "ptt_down"
    const val FIRST_PARTIAL = "first_partial"
    const val PTT_UP = "ptt_up"
    /** The tail ended and every captured frame of the utterance reached the recognizer. */
    const val AUDIO_DRAINED = "audio_drained"
    const val ASR_FINAL = "asr_final"
    const val PARSED = "parsed"
    /** The utterance was discarded (STOP, a new press, a language change, an overrun). */
    const val PTT_CANCELLED = "ptt_cancelled"
    const val PLAN_REJECTED = "plan_rejected"
    const val ENGINES_LOADED = "speech_engines_loaded"
}
