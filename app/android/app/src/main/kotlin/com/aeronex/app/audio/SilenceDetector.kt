package com.aeronex.app.audio

/**
 * Silenced-capture detection (spec 4.2). On Android 10+ another app can take the microphone; this app keeps running
 * but receives silence. Two detectors, either one means "No voice" (4.11):
 * - the platform's `AudioRecordingConfiguration.isClientSilenced()` (API 29+), reported through [setClientSilenced]
 *   from any thread;
 * - more than [zeroMs] of all-zero samples, on every API level, because the platform callback may not reach a
 *   silenced app.
 *
 * [accept] runs on the capture thread for every frame and allocates nothing.
 */
class SilenceDetector(zeroMs: Int = 500) {
    enum class Reason { NONE, CLIENT_SILENCED, DIGITAL_ZEROS }

    private val zeroSamples = AudioRing.samplesForMs(zeroMs).toLong()
    private var zeroRun = 0L

    @Volatile private var clientSilenced = false

    /** The reason as of the last [accept]. */
    var reason: Reason = Reason.NONE
        private set

    val silenced: Boolean get() = reason != Reason.NONE

    fun setClientSilenced(silenced: Boolean) {
        clientSilenced = silenced
    }

    /** Returns whether capture is silenced after this frame. */
    fun accept(frame: ShortArray, offset: Int, count: Int): Boolean {
        var allZero = true
        for (i in offset until offset + count) {
            if (frame[i].toInt() != 0) {
                allZero = false
                break
            }
        }
        zeroRun = if (allZero) zeroRun + count else 0L
        reason = when {
            clientSilenced -> Reason.CLIENT_SILENCED
            zeroRun > zeroSamples -> Reason.DIGITAL_ZEROS
            else -> Reason.NONE
        }
        return silenced
    }
}
