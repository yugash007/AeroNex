package com.aeronex.app.audio

/**
 * The adaptive post-release tail (spec 4.2, stage 2b): after PTT release, capture continues until the VAD has seen
 * [nonSpeechMs] of consecutive non-speech, or for at most [maxTailMs] (configurable 150-250 ms). Operators often
 * release before the last syllable; the tail keeps the unit word without making every command wait 250 ms.
 *
 * Driven by the speech thread with the ring's per-frame VAD flags, in order, starting with the frame at the release
 * sample. Positions are ring samples. Not thread-safe; allocates nothing.
 */
class TailEndpointer(
    nonSpeechMs: Int = 60,
    maxTailMs: Int = 250,
) {
    private val nonSpeechFrames: Int
    private val maxTailSamples: Long

    private var release = NOT_YET
    private var nextFrame = NOT_YET
    private var silentRun = 0

    /** The sample where the utterance ends (exclusive), or [NOT_YET]. */
    var end: Long = NOT_YET
        private set

    init {
        require(maxTailMs in 150..250) { "The tail maximum is 150-250 ms" }
        require(nonSpeechMs in AudioRing.FRAME_MS..maxTailMs) { "Non-speech time must be 20 ms up to the maximum" }
        nonSpeechFrames = (nonSpeechMs + AudioRing.FRAME_MS - 1) / AudioRing.FRAME_MS
        maxTailSamples = AudioRing.samplesForMs(maxTailMs).toLong()
    }

    /** PTT released at ring sample [releaseSample] (the capture position at the release event). */
    fun start(releaseSample: Long) {
        release = releaseSample
        nextFrame = releaseSample
        silentRun = 0
        end = NOT_YET
    }

    /**
     * The VAD decision of the next frame, which starts at [frameStart]. Returns the tail end once it is known (and
     * the same value on any later call), else [NOT_YET].
     */
    fun accept(frameStart: Long, speech: Boolean): Long {
        check(release != NOT_YET) { "start() first" }
        if (end != NOT_YET) return end
        require(frameStart == nextFrame) { "Frames must follow the release in order" }
        nextFrame = frameStart + AudioRing.FRAME_SAMPLES
        silentRun = if (speech) 0 else silentRun + 1
        val cap = release + maxTailSamples
        if (nextFrame >= cap) end = cap
        if (silentRun >= nonSpeechFrames) end = minOf(nextFrame, cap)
        return end
    }

    companion object {
        const val NOT_YET = -1L
    }
}
