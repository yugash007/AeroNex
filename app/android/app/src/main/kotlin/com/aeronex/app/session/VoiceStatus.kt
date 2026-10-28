package com.aeronex.app.session

import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.EnginePolicy

/** The speech level the UI always shows (spec 4.11). External mode and phone lost are the vehicle's and link's. */
enum class VoiceLevel { OK, DEGRADED, NO_VOICE }

/** What the speech thread is doing. */
enum class VoiceState {
    /** No capture: no session, no microphone permission, or not started yet. */
    OFF,
    /** Opening the language's engines (once per session and per language change). */
    LOADING,
    READY,
    /** Push-to-talk held. */
    LISTENING,
    /** Released: the adaptive tail, the final decode, the parser and the supervisor. */
    DECODING,
}

/** The voice stop spotter in use (spec 4.12 trigger 4). */
enum class SpotterKind { VOSK_STOP_GRAMMAR, KWS }

/** Why voice is not OK, with the operator text. The tap pad, typed commands and every physical STOP always work. */
enum class VoiceReason(val message: String) {
    NOT_STARTED("Connect to use voice commands. The tap pad and typed commands work without voice."),
    NO_MICROPHONE("Allow the microphone to use voice commands. The tap pad and typed commands work without it."),
    MICROPHONE_BLOCKED("The microphone could not start. Use the tap pad or typed commands."),
    MICROPHONE_SILENCED("Another app is using the microphone, so voice is off. Use the tap pad or typed commands."),
    CAPTURE_FAILED("The microphone stopped. Use the tap pad or typed commands, or reconnect."),
    LOADING("Loading the speech model…"),
    /** A voice pack this language needs is being downloaded or imported (single-flight, 0.4.0). */
    INSTALLING_PACK("Installing the voice pack…"),
    /** Spec 4.6: ta/kn/ml on Tier L until Phase 2.5. */
    NEEDS_NEWER_PHONE("Voice for this language needs a newer phone — use the tap pad"),
    NOT_INSTALLED(EnginePolicy.NOT_INSTALLED),
    UNSUPPORTED(EnginePolicy.UNSUPPORTED),
    ENGINE_FAILED("The speech model could not load. Use the tap pad or typed commands."),
    /** Voice degraded (4.11): the n-best margin refused several utterances in a row. */
    REPEATED_UNSURE("Voice commands were unclear several times. The tap pad and typed commands are more reliable now."),
}

/**
 * The snapshot's `speech` section (see [SessionSchema]). [utterance] counts PTT presses in this process (0 before the
 * first), so the client can tell a new [heard]/[error] from the last one.
 */
data class VoiceStatus(
    val language: String,
    val state: VoiceState,
    val level: VoiceLevel,
    val reason: VoiceReason?,
    val engine: EngineKind? = null,
    val spotter: SpotterKind? = null,
    /** Voice STOP in the operator's language (Vosk stop grammar); the KWS knows English "stop" only. */
    val nativeVoiceStop: Boolean = false,
    /** A stop word heard while PTT is held stops at once (streaming engines only, spec 4.12 trigger 5). */
    val partialStop: Boolean = false,
    /** The voice stop spotter is listening now: vehicle armed or airborne and the app owns the mode. */
    val spotterArmed: Boolean = false,
    val pttHeld: Boolean = false,
    val utterance: Long = 0,
    /** The live partial transcript while PTT is held (streaming engines). */
    val partial: String? = null,
    /** The last utterance's best transcript. */
    val heard: String? = null,
    /** Why the last utterance did not become a plan ("say again", a rule message, voice unavailable). */
    val error: String? = null,
) {
    companion object {
        fun off(language: String, reason: VoiceReason) = VoiceStatus(language, VoiceState.OFF, VoiceLevel.NO_VOICE, reason)
    }
}
