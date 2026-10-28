package com.aeronex.app.bench

import com.aeronex.app.audio.AudioRing
import com.aeronex.app.command.ParseResult
import com.aeronex.app.session.VoiceSession
import com.aeronex.app.speech.PttUtterance
import com.aeronex.app.speech.SpeechResult
import com.aeronex.app.speech.SpotterGate
import java.util.concurrent.TimeUnit

enum class PttStatus { ENDED, STOP_TOKEN, OVERRUN, TIMEOUT }

/**
 * The push-to-talk side of one clip. Times are wall ms on the bench (speech) thread; `ptt_up` is the moment the
 * clip's last frame was captured (the operator releases as the clip ends). [rtf] = recognizer compute time
 * (streaming accept + final decode) / [audioMs] (pre-roll + clip + tail).
 */
data class PttOutcome(
    val status: PttStatus,
    val result: SpeechResult?,
    val audioMs: Double,
    /** ptt_up → audio_drained: the real-time adaptive tail plus the last frames' decode. */
    val pttUpToDrainedMs: Double?,
    /** audio_drained → asr_final: the final decode ([com.aeronex.app.speech.SpeechEngine.finish]). */
    val drainedToFinalMs: Double?,
    val pttUpToFinalMs: Double?,
    /** asr_final → parsed ([VoiceSession.interpret]: n-best margin, TranscriptInspector, PlanParser). */
    val parseMs: Double?,
    val pttUpToParsedMs: Double?,
    /** When a partial stop token ended PTT, relative to the release (negative: before it). */
    val stopTokenAfterReleaseMs: Double?,
    val acceptMs: Double,
    val finishMs: Double,
    val rtf: Double?,
)

/** The voice stop spotter on one clip, fed through [SpotterGate] (armed for the whole clip: the in-flight case). */
data class SpotterOutcome(
    val detections: Int,
    val firstAfterClipStartMs: Double?,
    val firstAfterReleaseMs: Double?,
    /** Audio the VAD gate fed the spotter. */
    val fedMs: Double,
    val computeMs: Double,
    val rtf: Double?,
)

data class ClipOutcome(
    val clip: ClipInfo,
    val startSample: Long,
    val endSample: Long,
    val ptt: PttOutcome?,
    val parsed: ParseResult?,
    val verdict: Verdict?,
    val spotter: SpotterOutcome?,
    val spotterVerdict: SpotterVerdict?,
)

/**
 * Runs clips through the service's speech path (plan T14): [player] feeds [ring]'s capture at real time; the command
 * recognizer reads the ring through [PttUtterance] (500 ms pre-roll, release at the clip's end, adaptive tail) and
 * the spotter through [SpotterGate], both on this (speech) thread as in [VoiceSession]. A partial stop token cancels
 * the utterance, as the service does. Clips are separated by at least [settleMs] (and the 500 ms pre-roll) of silence,
 * so a pre-roll never holds the previous clip.
 */
class ClipBench(
    private val ring: AudioRing,
    private val player: ClipPlayer,
    private val engine: TimedEngine?,
    private val spotter: TimedSpotter?,
    private val settleMs: Int = 800,
    private val timeoutMs: Long = 30_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val gate = spotter?.let { SpotterGate(ring, it) }
    private val settleFrames = settleMs / AudioRing.FRAME_MS.toLong()
    private val gapFrames = maxOf(settleMs, AudioRing.PTT_PRE_ROLL_MS) / AudioRing.FRAME_MS.toLong()
    /** The frame from which the ring has been quiet (the previous clip's end). */
    private var quietFrom = 0L

    fun run(clip: BenchClip): ClipOutcome {
        waitForFrames(quietFrom + gapFrames)
        val playback = player.play(clip.samples)
        val utterance = engine?.let { PttUtterance(ring, it) }
        utterance?.press(playback.startSample)
        utterance?.release(playback.endSample)
        gate?.start(playback.startFrame)
        spotter?.resetTimers()

        var status: PttStatus? = if (utterance == null) null else PttStatus.TIMEOUT
        var pending = utterance != null
        var result: SpeechResult? = null
        var parsed: ParseResult? = null
        var drainedAt: Long? = null
        var finalAt: Long? = null
        var parsedAt: Long? = null
        var stopTokenAt: Long? = null
        var detections = 0
        var firstDetection: Long? = null
        val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos((clip.samples.size / SAMPLES_PER_MS) + timeoutMs)
        while (true) {
            val seen = ring.framesWritten
            if (pending) {
                when (checkNotNull(utterance).poll()) {
                    PttUtterance.Status.LISTENING -> {}
                    PttUtterance.Status.STOP_TOKEN -> {
                        stopTokenAt = nanoTime()
                        utterance.cancel()
                        status = PttStatus.STOP_TOKEN
                        pending = false
                    }
                    PttUtterance.Status.OVERRUN -> {
                        utterance.cancel()
                        status = PttStatus.OVERRUN
                        pending = false
                    }
                    PttUtterance.Status.ENDED -> {
                        // As VoiceSession.finish: decode, then parse at once on the speech thread.
                        drainedAt = nanoTime()
                        val final = utterance.finish()
                        finalAt = nanoTime()
                        parsed = VoiceSession.interpret(final)
                        parsedAt = nanoTime()
                        result = final
                        status = PttStatus.ENDED
                        pending = false
                    }
                }
            }
            if (gate?.poll() == true) {
                detections++
                if (firstDetection == null) firstDetection = nanoTime()
            }
            if (!pending && ring.framesWritten >= playback.endFrame + settleFrames) break
            if (nanoTime() > deadline) {
                if (pending) utterance?.cancel()
                break
            }
            ring.awaitFrames(seen + 1, WAIT_NANOS)
        }
        quietFrom = playback.endFrame

        val release = playback.endedAtNanos.takeIf { playback.ended }
        val clipStart = playback.clipStartNanos.takeIf { playback.startedAtNanos != ClipPlayer.NOT_YET }
        fun since(from: Long?, to: Long?) = if (from == null || to == null) null else (to - from) / 1e6
        val ptt = engine?.let {
            val audioSamples = result?.audioSamples ?: it.acceptedSamples
            val audioMs = audioSamples / SAMPLES_PER_MS.toDouble()
            PttOutcome(
                status = checkNotNull(status),
                result = result,
                audioMs = audioMs,
                pttUpToDrainedMs = since(release, drainedAt),
                drainedToFinalMs = since(drainedAt, finalAt),
                pttUpToFinalMs = since(release, finalAt),
                parseMs = since(finalAt, parsedAt),
                pttUpToParsedMs = since(release, parsedAt),
                stopTokenAfterReleaseMs = since(release, stopTokenAt),
                acceptMs = it.acceptNanos / 1e6,
                finishMs = it.finishNanos / 1e6,
                rtf = if (audioMs > 0 && result != null) (it.acceptNanos + it.finishNanos) / 1e6 / audioMs else null,
            )
        }
        val heard = spotter?.let {
            val fedMs = it.fedSamples / SAMPLES_PER_MS.toDouble()
            SpotterOutcome(detections, since(clipStart, firstDetection), since(release, firstDetection),
                fedMs, it.acceptNanos / 1e6, if (fedMs > 0) it.acceptNanos / 1e6 / fedMs else null)
        }
        return ClipOutcome(
            clip = clip.info,
            startSample = playback.startSample,
            endSample = playback.endSample,
            ptt = ptt,
            parsed = parsed,
            verdict = if (engine == null) null else Expectation.verdict(clip.info.id, status == PttStatus.STOP_TOKEN, parsed),
            spotter = heard,
            spotterVerdict = heard?.let { Expectation.spotterVerdict(clip.info.id, it.detections) },
        )
    }

    /** Waits for the capture to reach [frames]; a capture that stops delivering fails the clip instead of hanging. */
    private fun waitForFrames(frames: Long) {
        var progress = ring.framesWritten
        var stalledSince = nanoTime()
        while (ring.framesWritten < frames) {
            ring.awaitFrames(frames, WAIT_NANOS)
            if (ring.framesWritten != progress) {
                progress = ring.framesWritten
                stalledSince = nanoTime()
            }
            check(nanoTime() - stalledSince < STALL_NANOS) { "The capture stopped delivering frames" }
        }
    }

    private companion object {
        const val SAMPLES_PER_MS = AudioRing.SAMPLE_RATE / 1000
        val WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(20)
        val STALL_NANOS = TimeUnit.SECONDS.toNanos(5)
    }
}
