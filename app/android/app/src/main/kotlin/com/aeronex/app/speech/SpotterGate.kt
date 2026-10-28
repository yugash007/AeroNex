package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing

/**
 * Feeds the voice stop spotter from the capture ring (spec 4.2, 4.12), on the speech thread:
 * - only while the VAD has a speech segment open, plus [hangoverMs] after its last speech frame (a keyword spotter
 *   needs trailing audio to finish the word: chunk-16 looks about 320 ms ahead);
 * - each segment starts with [StopSpotter.reset] and a replay of the [preRollMs] (300 ms) before the onset, so the
 *   onset of "stop" is not lost, but never audio from before [start] or audio already fed;
 * - after a detection the rest of that segment is skipped, so one word fires once;
 * - when the hangover runs out the spotter decides on the rest ([StopSpotter.endSegment]): on the emulator Vosk's stop
 *   partial came 420-520 ms after the word, so its second partial step often fell after the hangover (T14 bench).
 * A gate that falls out of the ring's readable window resynchronises to the oldest readable frame.
 */
class SpotterGate(
    private val ring: AudioRing,
    private val spotter: StopSpotter,
    preRollMs: Int = AudioRing.SPOTTER_PRE_ROLL_MS,
    hangoverMs: Int = 600,
) {
    private enum class State { IDLE, FEEDING, AFTER_DETECTION }

    private val preRollFrames = preRollMs / AudioRing.FRAME_MS
    private val hangoverFrames = hangoverMs / AudioRing.FRAME_MS
    private val frame = ShortArray(AudioRing.FRAME_SAMPLES)
    private var next = 0L
    /** The first frame that may still be fed (nothing before it is ever replayed). */
    private var floor = 0L
    private var silentRun = 0
    private var state = State.IDLE

    /** Arms the gate at [fromFrame] (default: now), e.g. when the spotter becomes armed or PTT is released. */
    fun start(fromFrame: Long = ring.framesWritten) {
        next = fromFrame
        floor = fromFrame
        silentRun = 0
        state = State.IDLE
    }

    /** Processes every frame captured since the last call. Returns true when the spotter detected STOP. */
    fun poll(): Boolean {
        frames@ while (next < ring.framesWritten) {
            val oldest = oldestFrame()
            if (next < oldest) {
                resync(oldest)
                continue
            }
            val speech = ring.speechAt(next) ?: continue
            when (state) {
                State.AFTER_DETECTION -> {
                    floor = next + 1
                    if (!speech) state = State.IDLE
                }
                State.IDLE -> if (speech) {
                    spotter.reset()
                    state = State.FEEDING
                    silentRun = 0
                    // The replay, then the onset frame itself.
                    for (index in maxOf(next - preRollFrames, floor, oldest)..next) {
                        when (feed(index)) {
                            Fed.DETECTED -> return true
                            Fed.OVERRUN -> continue@frames
                            Fed.OK -> {}
                        }
                    }
                }
                State.FEEDING -> {
                    silentRun = if (speech) 0 else silentRun + 1
                    when (feed(next)) {
                        Fed.DETECTED -> return true
                        Fed.OVERRUN -> continue@frames
                        Fed.OK -> if (silentRun >= hangoverFrames) {
                            state = State.IDLE
                            if (spotter.endSegment()) {
                                next++
                                return true
                            }
                        }
                    }
                }
            }
            next++
        }
        return false
    }

    private enum class Fed { OK, DETECTED, OVERRUN }

    /** Feeds frame [index]. A detection skips the rest of the segment; an overrun resynchronises. */
    private fun feed(index: Long): Fed {
        val n = ring.read(index * AudioRing.FRAME_SAMPLES, frame, 0, frame.size)
        if (n != frame.size) {
            resync(oldestFrame())
            return Fed.OVERRUN
        }
        floor = index + 1
        if (!spotter.accept(frame, 0, frame.size)) return Fed.OK
        state = State.AFTER_DETECTION
        return Fed.DETECTED
    }

    private fun oldestFrame() = maxOf(0L, ring.framesWritten - ring.readableFrames)

    private fun resync(oldest: Long) {
        next = maxOf(next, oldest)
        floor = maxOf(floor, oldest)
        state = State.IDLE
    }
}
