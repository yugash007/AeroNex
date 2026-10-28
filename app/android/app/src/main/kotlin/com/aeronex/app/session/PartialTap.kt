package com.aeronex.app.session

import com.aeronex.app.speech.VoskJson
import com.aeronex.app.speech.VoskModelHandle
import com.aeronex.app.speech.VoskRecognizerHandle

/**
 * Reads the live partial transcript for the PTT bar off the command-grammar recognizer (the one created with
 * [commandGrammar]); the stop-grammar recognizer is passed through untouched. [text] is the utterance so far: the
 * endpoint segments already finished plus the current partial; it empties when the recognizer is reset (a new
 * utterance). `[unk]` is never shown. Display only: the plan always comes from the final result. Speech thread only.
 */
class PartialTap(private val model: VoskModelHandle, private val commandGrammar: String) : VoskModelHandle {
    @Volatile var text: String = ""
        private set

    override fun recognizer(grammarJson: String): VoskRecognizerHandle {
        val recognizer = model.recognizer(grammarJson)
        return if (grammarJson == commandGrammar) Tapped(recognizer) else recognizer
    }

    override fun close() = model.close()

    private inner class Tapped(private val inner: VoskRecognizerHandle) : VoskRecognizerHandle by inner {
        private var finished = ""

        override fun result(): String = inner.result().also {
            finished = join(finished, VoskJson.alternatives(it).firstOrNull()?.text.orEmpty())
            text = finished
        }

        override fun partialResult(): String = inner.partialResult().also {
            text = join(finished, VoskJson.partialWords(it).joinToString(" "))
        }

        override fun reset() {
            inner.reset()
            finished = ""
            text = ""
        }
    }

    /** [unk] marks out-of-grammar audio: never shown as words. */
    private fun join(a: String, b: String) =
        (a.split(' ') + b.split(' ')).filter { it.isNotBlank() && it != UNK }.joinToString(" ")

    private companion object {
        const val UNK = "[unk]"
    }
}
