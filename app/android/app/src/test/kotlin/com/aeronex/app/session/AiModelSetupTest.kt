package com.aeronex.app.session

import com.aeronex.app.ai.AiModelStore
import com.aeronex.app.ai.AiModels
import com.aeronex.app.vehicle.executor.waitFor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Models screen's AI rows over the session channel: status, import from the import folder, cancel, remove. */
@Suppress("UNCHECKED_CAST")
class AiModelSetupTest {
    private val root = Files.createTempDirectory("ai-models").toFile()
    private val importRoot = File(root, "import")
    private val removedFirst = AtomicInteger()
    private val changes = AtomicInteger()
    private val setup = AiModelSetup(AiModelStore(File(root, "models").also { it.mkdirs() }), importRoot,
        beforeRemove = { removedFirst.incrementAndGet() }, changed = { changes.incrementAndGet() })
    private val ai = AiStatus.OFF.copy(reason = "Download the AI models in Models")

    @After fun tearDown() {
        setup.close()
        root.deleteRecursively()
    }

    private fun models() = setup.status(ai)["models"] as List<Map<String, Any?>>

    @Test fun bothModelsAreListedWithTheirSizeStateAndImportPath() {
        val status = setup.status(ai)
        assertEquals(false, status["available"])
        assertEquals("Download the AI models in Models", status["reason"])
        assertEquals(listOf(AiModels.LFM, AiModels.NEEDLE), models().map { it["id"] })
        assertEquals(listOf(229_313_568L, 35_335_380L), models().map { it["bytes"] })
        assertTrue(models().all { it["state"] == "missing" })
        assertEquals(File(File(importRoot, AiModels.LFM), "LFM2.5-VL-450M-Q4_K_M.gguf").path, models()[0]["importPath"])
    }

    @Test fun anImportNeedsTheFileInItsFolderAndAWrongFileIsRefused() {
        val missing = runCatching { setup.import(AiModels.NEEDLE) }.exceptionOrNull() as SessionException
        assertEquals(SessionException.BAD_ARGS, missing.code)
        assertTrue(missing.message, missing.message.startsWith("Copy needle3.cact to "))
        File(importRoot, AiModels.NEEDLE).mkdirs()
        File(File(importRoot, AiModels.NEEDLE), "needle3.cact").writeText("not the model")
        setup.import(AiModels.NEEDLE)
        waitFor("import ended") { setup.status(ai)["transfer"] == null && setup.status(ai)["error"] != null }
        val error = setup.status(ai)["error"] as Map<String, Any?>
        assertEquals(AiModels.NEEDLE, error["id"])
        assertNotNull(error["message"])
        assertEquals("missing", models()[1]["state"])
        assertTrue(changes.get() >= 2)
    }

    @Test fun removingStopsTheAiFirstAndUnknownIdsAreRefused() {
        assertFalse(setup.remove(AiModels.LFM))
        assertEquals(1, removedFirst.get())
        val unknown = runCatching { setup.remove("vosk-en-in") }.exceptionOrNull() as SessionException
        assertEquals(SessionException.BAD_ARGS, unknown.code)
    }

    @Test fun withoutAiModelsTheChannelSaysSo() {
        val api = SessionApi(SessionController())
        val reply = api.call("aiModels", null) as SessionApi.Reply.Ok
        assertEquals(emptyList<Any>(), (reply.value as Map<String, Any?>)["models"])
        assertEquals(SessionException.BAD_ARGS, (api.call("downloadAiModel", mapOf("id" to AiModels.LFM)) as SessionApi.Reply.Failed).code)
        assertEquals(SessionApi.Reply.Ok(null), api.call("cancelAiModel", null))
    }
}
