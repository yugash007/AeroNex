package com.aeronex.app.audio

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.util.concurrent.Executor

/**
 * The phone microphone (spec 4.2): `AudioRecord` on `VOICE_RECOGNITION` (the CDD keeps noise suppression and AGC
 * off for it), 16 kHz mono PCM16, blocking reads. On API 29+ an `AudioRecordingCallback` is registered before
 * `startRecording()` so the platform's "client silenced" state reaches the [SilenceDetector].
 *
 * The caller must hold RECORD_AUDIO (requested by the service wiring, T12). Not verified on a device here (T14).
 */
class AndroidPcmSource : PcmSource {
    @Volatile private var record: AudioRecord? = null
    private var callback: Any? = null

    @SuppressLint("MissingPermission")
    override fun start(onClientSilenced: (Boolean) -> Unit) {
        val minBytes = AudioRecord.getMinBufferSize(AudioRing.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT)
        // At least 200 ms of HAL buffering: a short scheduling stall must not drop audio.
        val bytes = maxOf(minBytes * 2, AudioRing.samplesForMs(200) * 2)
        val created = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioRing.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bytes)
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            created.release()
            throw IllegalStateException("The microphone could not be opened (RECORD_AUDIO granted?)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callback = SilencedCallback(created, onClientSilenced).also {
                created.registerAudioRecordingCallback(Executor(Runnable::run), it)
            }
        }
        record = created
        created.startRecording()
    }

    override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
        val current = record ?: return 0
        return current.read(buffer, offset, count, AudioRecord.READ_BLOCKING)
    }

    /** Unblocks the capture thread's read; [release] (on that thread) frees the recorder afterwards. */
    override fun stop() {
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Never started or already released.
        }
    }

    override fun release() {
        val current = record ?: return
        record = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (callback as? AudioManager.AudioRecordingCallback)?.let(current::unregisterAudioRecordingCallback)
        }
        callback = null
        current.release()
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private class SilencedCallback(
        private val record: AudioRecord,
        private val report: (Boolean) -> Unit,
    ) : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val session = record.audioSessionId
            configs.firstOrNull { it.clientAudioSessionId == session }?.let { report(it.isClientSilenced) }
        }
    }

    companion object {
        /** For [AudioCapture]'s thread setup on a phone. */
        fun urgentAudioPriority() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        }
    }
}
