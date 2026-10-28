package com.aeronex.app.session

import com.aeronex.app.command.Normalizer
import com.aeronex.app.safety.PlanSource
import com.aeronex.app.safety.StopTrigger
import com.aeronex.app.session.SessionSchema.wire

/**
 * The `aeronex/session` MethodChannel's methods, without Flutter ([SessionChannel] adapts them). See [SessionSchema]
 * for the maps.
 *
 * - `connect(config)` → snapshot; `disconnect` → null.
 * - `setExecution(bool | {enabled})` → `{enabled}` (stays false without a link).
 * - `submitPlan(plan)` → see [SessionSchema.submitted]; `confirm(token | {token})` → `{accepted, error?}`.
 * - `submitText({text, language (alias lang), source?})` → as `submitPlan`: the text is parsed by the Kotlin
 *   [com.aeronex.app.command.PlanParser.parseText]; `language` is "te-IN", "te" or a language name;
 *   `source` is `typed` (default), `phone` or `tap`. A parser rejection is an `{error}` answer.
 * - `cancel` (STOP while executing), `stop({heldOneSecond}?)` (the on-screen button), `acknowledgeAlarm` → null.
 * - `requestTelemetry` → bool; `getSnapshot` → snapshot.
 * - `pttDown`, `pttUp` → null (T12): push-to-talk from the PTT bar; the result arrives in the snapshot's `speech`
 *   section and as a plan (Phone source). Nothing happens while not connected or without voice.
 * - `setLanguage({language} | language)` → `{language: code}`: the speech language (saved; reloads the engines).
 * - `flightScreen({visible} | bool)` → null: whether the Flight screen is in front (Volume-Up is PTT only then).
 * - `microphonePermission({ask})` and `notificationPermission({ask})`: answered by the activity ([SessionChannel]).
 * - 0.4.0 AI: `cancelAi` → null (the "Thinking…" Cancel). `aiModels` → `{available, reason, models: [{id, file, bytes,
 *   state, importPath}], transfer?, error?}`; `downloadAiModel({id})`, `importAiModel({id})` → `{started: true}` (the
 *   progress and outcome show in `aiModels`); `cancelAiModel` → null; `removeAiModel({id})` → `{removed}`.
 */
class SessionApi(private val controller: SessionController) {
    sealed interface Reply {
        data class Ok(val value: Any?) : Reply
        data class Failed(val code: String, val message: String) : Reply
        data object NotImplemented : Reply
    }

    fun call(method: String, arguments: Any?): Reply = try {
        when (method) {
            "connect" -> Reply.Ok(controller.connect(SessionConfig.fromMap(arguments)))
            "disconnect" -> done { controller.disconnect() }
            "setExecution" -> Reply.Ok(mapOf("enabled" to controller.setExecution(argument(arguments, "enabled") as? Boolean
                ?: throw bad("setExecution needs true or false."))))
            "submitPlan" -> Reply.Ok(controller.submitPlan(arguments))
            "submitText" -> Reply.Ok(submitText(arguments))
            "confirm" -> Reply.Ok(controller.confirm(argument(arguments, "token") as? String
                ?: throw bad("confirm needs the plan token.")))
            "cancel" -> done { controller.cancel() }
            "stop" -> done { controller.stop(StopTrigger.BUTTON, argument(arguments, "heldOneSecond") == true) }
            "acknowledgeAlarm" -> done { controller.acknowledgeAlarm() }
            "requestTelemetry" -> Reply.Ok(controller.requestTelemetry())
            "pttDown" -> done { controller.pttDown() }
            "pttUp" -> done { controller.pttUp() }
            "setLanguage" -> Reply.Ok(mapOf("language" to controller.setLanguage(argument(arguments, "language") as? String
                ?: throw bad("setLanguage needs the language."))))
            "flightScreen" -> done {
                controller.flightScreenVisible = argument(arguments, "visible") as? Boolean
                    ?: throw bad("flightScreen needs true or false.")
            }
            "getSnapshot" -> Reply.Ok(controller.snapshot())
            "cancelAi" -> done { controller.cancelAi() }
            "aiModels" -> Reply.Ok(controller.aiModels())
            "downloadAiModel" -> started { controller.downloadAiModel(modelId(arguments)) }
            "importAiModel" -> started { controller.importAiModel(modelId(arguments)) }
            "cancelAiModel" -> done { controller.cancelAiModel() }
            "removeAiModel" -> Reply.Ok(mapOf("removed" to controller.removeAiModel(modelId(arguments))))
            else -> Reply.NotImplemented
        }
    } catch (e: SessionException) {
        Reply.Failed(e.code, e.message)
    } catch (e: IllegalArgumentException) {
        Reply.Failed(SessionException.BAD_ARGS, e.message ?: "Bad arguments.")
    }

    private fun submitText(arguments: Any?): Map<String, Any?> {
        val map = arguments as? Map<*, *> ?: throw bad("submitText needs {text, language}.")
        val text = map["text"] as? String ?: throw bad("submitText needs the text.")
        val language = (map["language"] ?: map["lang"]) as? String ?: throw bad("submitText needs the language.")
        if (Normalizer.languageCode(language) == null) throw bad("Unknown language: $language")
        val source = map["source"]?.let { wire ->
            PlanSource.fromChannel.firstOrNull { it.wire == wire } ?: throw bad("Unknown plan source: $wire")
        }
        return controller.submitText(text, language, source ?: PlanSource.TYPED)
    }

    companion object {
        private val INLINE = setOf("stop", "cancel", "acknowledgeAlarm", "pttDown", "pttUp", "flightScreen", "cancelAi",
            "cancelAiModel")

        /**
         * Answered on the calling (main) thread: they only post to the supervisor or queue for the speech thread, so a
         * STOP or a PTT edge never waits behind a slow connect or disconnect on the channel's worker.
         */
        fun isInline(method: String) = method in INLINE

        private inline fun done(action: () -> Unit): Reply {
            action()
            return Reply.Ok(null)
        }

        private inline fun started(action: () -> Unit): Reply {
            action()
            return Reply.Ok(mapOf("started" to true))
        }

        private fun modelId(arguments: Any?): String = argument(arguments, "id") as? String ?: throw bad("Name the AI model.")

        /** A bare value, or the value under [key] when the client sent a map. */
        private fun argument(arguments: Any?, key: String): Any? = if (arguments is Map<*, *>) arguments[key] else arguments

        private fun bad(message: String) = SessionException(SessionException.BAD_ARGS, message)
    }
}
