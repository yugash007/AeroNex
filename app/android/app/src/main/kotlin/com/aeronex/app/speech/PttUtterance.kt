package com.aeronex.app.speech

import com.aeronex.app.audio.AudioRing
import com.aeronex.app.audio.TailEndpointer

/**
 * One push-to-talk utterance, read from the capture ring into a [SpeechEngine] on the speech thread (spec 4.2):
 * [press] backdates the start by 500 ms of pre-roll; after [release] the adaptive [tail] decides where it ends (60 ms
 * of VAD non-speech, at most 250 ms). The caller polls after each captured frame (e.g. [AudioRing.awaitFrames])
 * until the status is not [Status.LISTENING], then calls [finish] (ENDED) or [cancel] (STOP_TOKEN, OVERRUN, or a
 * STOP key during PTT).
 */
class PttUtterance(
    private val ring: AudioRing,
    private val engine: SpeechEngine,
    private val tail: TailEndpointer = TailEndpointer(),
) {
    enum class Status {
        LISTENING,
        /** A stop token in two consecutive partials (streaming engines): run STOP, end PTT, discard the utterance. */
        STOP_TOKEN,
        /** Release + tail reached: [finish] decodes. */
        ENDED,
        /** The speech thread fell more than the ring's window behind: the audio has a gap; discard ("say again"). */
        OVERRUN,
    }

    private val chunk = ShortArray(AudioRing.FRAME_SAMPLES)
    private var position = NONE
    private var nextTailFrame = NONE
    private var end = TailEndpointer.NOT_YET

    /** PTT down at capture position [at]. */
    fun press(at: Long = ring.writePosition) {
        engine.begin()
        position = ring.preRollStart(at)
        nextTailFrame = NONE
        end = TailEndpointer.NOT_YET
    }

    /** PTT up at capture position [at] (a frame boundary: the capture position at the release event). */
    fun release(at: Long = ring.writePosition) {
        check(position != NONE) { "press() first" }
        tail.start(at)
        nextTailFrame = at / AudioRing.FRAME_SAMPLES
    }

    /** Feeds everything captured since the last call, up to the tail end once it is known. */
    fun poll(): Status {
        check(position != NONE) { "press() first" }
        if (end != TailEndpointer.NOT_YET && position >= end) return Status.ENDED
        while (nextTailFrame != NONE && end == TailEndpointer.NOT_YET && nextTailFrame < ring.framesWritten) {
            val speech = ring.speechAt(nextTailFrame) ?: return Status.OVERRUN
            end = tail.accept(nextTailFrame * AudioRing.FRAME_SAMPLES, speech)
            nextTailFrame++
        }
        val limit = if (end != TailEndpointer.NOT_YET) end else ring.writePosition
        while (position < limit) {
            val n = ring.read(position, chunk, 0, minOf(chunk.size.toLong(), limit - position).toInt())
            if (n == AudioRing.OVERRUN) return Status.OVERRUN
            if (n == 0) break
            position += n
            if (engine.accept(chunk, 0, n)) return Status.STOP_TOKEN
        }
        return if (end != TailEndpointer.NOT_YET && position >= end) Status.ENDED else Status.LISTENING
    }

    fun finish(): SpeechResult = engine.finish()

    fun cancel() = engine.cancel()

    private companion object {
        const val NONE = -1L
    }
}
