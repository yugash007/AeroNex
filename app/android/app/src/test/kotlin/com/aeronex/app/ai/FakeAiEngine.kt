package com.aeronex.app.ai

/** A scripted stand-in for libaeronex_ai.so: records every call and answers with the given JSON. */
class FakeAiEngine : AiEngine {
    val calls = mutableListOf<String>()
    @Volatile var nativeCancelled = false
    var planReply: (String) -> String = { planJson(emptyList()) }
    var toolReply: (String) -> String = { """{"call":null,"reason":"no_call","ms":1.0}""" }

    override fun reset() {
        calls += "reset"
        nativeCancelled = false
    }

    override fun cancel() {
        calls += "cancel"
        nativeCancelled = true
    }

    override fun plan(model: ByteArray, text: ByteArray, threads: Int, timeoutMs: Int): ByteArray {
        calls += "plan ${String(model)} | ${String(text)} | $threads | $timeoutMs"
        return planReply(String(text)).toByteArray()
    }

    override fun warmUp(model: ByteArray, threads: Int): Double {
        calls += "warmUp ${String(model)} | $threads"
        return 12.5
    }

    override fun unload() {
        calls += "unload"
    }

    override fun toolCall(model: ByteArray, step: ByteArray): ByteArray {
        calls += "toolCall ${String(model)} | ${String(step)}"
        return toolReply(String(step)).toByteArray()
    }

    companion object {
        fun planJson(steps: List<String>, complete: Boolean = true, stop: String = "done"): String {
            val list = steps.joinToString(",") { "\"$it\"" }
            val text = "{\\\"steps\\\":[" + steps.joinToString(",") { "\\\"$it\\\"" } + "]}"
            return """{"complete":$complete,"steps":[$list],"text":"$text","stop":"$stop","promptTokens":420,""" +
                """"generatedTokens":17,"loadMs":0.0,"promptMs":35.5,"generateMs":210.25}"""
        }

        fun callJson(name: String, arguments: String = "{}") =
            """{"call":{"name":"$name","arguments":$arguments},"reason":"","ms":80.0}"""
    }
}
