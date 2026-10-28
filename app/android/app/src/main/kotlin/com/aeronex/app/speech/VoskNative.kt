package com.aeronex.app.speech

import com.aeronex.app.LocalModelDirectoryLocks
import java.io.File
import java.util.concurrent.locks.Lock
import org.vosk.Model
import org.vosk.Recognizer

/**
 * vosk-android 0.3.75 behind [VoskModelHandle] (JNA loads libvosk, so JVM tests use fakes). The model directory's
 * read lease is taken before the model loads and released after it closes, so a model re-install waits for the
 * session (VerifiedModelStore contract). A ReentrantReadWriteLock lease belongs to its thread: open and close the
 * engine on the same (speech) thread.
 */
class VoskNativeModel(modelDirectory: File) : VoskModelHandle {
    private val lease: Lock = LocalModelDirectoryLocks.forDirectory(checkNotNull(modelDirectory.parentFile)).readLock()
    private val model: Model

    init {
        lease.lock()
        model = try {
            Model(modelDirectory.absolutePath)
        } catch (error: Throwable) {
            lease.unlock()
            throw error
        }
    }

    override fun recognizer(grammarJson: String): VoskRecognizerHandle =
        VoskNativeRecognizer(Recognizer(model, SAMPLE_RATE, grammarJson))

    override fun close() {
        try {
            model.close()
        } finally {
            lease.unlock()
        }
    }

    private class VoskNativeRecognizer(private val recognizer: Recognizer) : VoskRecognizerHandle {
        override fun setMaxAlternatives(max: Int) = recognizer.setMaxAlternatives(max)
        override fun setPartialWords(enabled: Boolean) = recognizer.setPartialWords(enabled)
        override fun acceptWaveForm(samples: ShortArray, count: Int) = recognizer.acceptWaveForm(samples, count)
        override fun result(): String = recognizer.result
        override fun partialResult(): String = recognizer.partialResult
        override fun finalResult(): String = recognizer.finalResult
        override fun reset() = recognizer.reset()
        override fun close() = recognizer.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000f
    }
}
