package com.aeronex.app.ai

/**
 * The JNI surface of libaeronex_ai.so (app/android/app/src/main/cpp/ai_jni.cpp), arm64-v8a only. Strings cross as
 * UTF-8 bytes and answers come back as UTF-8 JSON; every method may throw [IllegalStateException] with an
 * operator-facing message. One process-wide native state: a cancel flag shared by [plan] and [toolCall], one loaded
 * planner model and one mapped Needle model. JVM tests use a fake.
 */
interface AiEngine {
    /** Clears a previous [cancel]. Call it before checking the request's own cancel token, then [plan]. */
    fun reset()

    /** Aborts a running [plan] at its next token or graph node and makes a following [toolCall] fail. Any thread. */
    fun cancel()

    /**
     * One planner run: `{"complete", "steps": [...], "text", "stop": "done|limit|cancelled|timeout", "promptTokens",
     * "generatedTokens", "loadMs", "promptMs", "generateMs"}`. Loads [model] on first use (mmap) and keeps it loaded.
     */
    fun plan(model: ByteArray, text: ByteArray, threads: Int, timeoutMs: Int): ByteArray

    /** Loads [model] and evaluates the planner's fixed instructions without a deadline; returns milliseconds. */
    fun warmUp(model: ByteArray, threads: Int): Double

    /** Frees the planner's weights and context; waits for a running [plan]. */
    fun unload()

    /** One Needle tool call for one step: `{"call": {"name", "arguments"} | null, "reason", "ms"}`. */
    fun toolCall(model: ByteArray, step: ByteArray): ByteArray
}

/** The real engine. Obtain it through [NativeAiEngine.load], never on an ABI without libaeronex_ai.so. */
class NativeAiEngine private constructor() : AiEngine {
    external override fun reset()
    external override fun cancel()
    external override fun plan(model: ByteArray, text: ByteArray, threads: Int, timeoutMs: Int): ByteArray
    external override fun warmUp(model: ByteArray, threads: Int): Double
    external override fun unload()
    external override fun toolCall(model: ByteArray, step: ByteArray): ByteArray

    companion object {
        private val loaded: Result<NativeAiEngine> by lazy {
            runCatching {
                System.loadLibrary("aeronex_ai")
                NativeAiEngine()
            }
        }

        /** The process's engine, or null when libaeronex_ai.so cannot load (32-bit or x86_64 APK, broken install). */
        fun load(): NativeAiEngine? = loaded.getOrNull()
    }
}
