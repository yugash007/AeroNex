package com.aeronex.app.session

import com.aeronex.app.VoskModels
import com.aeronex.app.speech.CtcDecoder
import com.aeronex.app.speech.CtcModelSpec
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.DeviceTier
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.GrammarAssets
import com.aeronex.app.speech.KwsDecoder
import com.aeronex.app.speech.KwsModelSpec
import com.aeronex.app.speech.ModelLoad
import com.aeronex.app.speech.ModelPackStore
import com.aeronex.app.speech.PackEngine
import com.aeronex.app.speech.PackFile
import com.aeronex.app.speech.SpeechModelPack
import com.aeronex.app.speech.VoskGrammarEngineTest
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The phone's voice wiring with the native loaders faked: the 0.4.0 Telugu tiers, lazy CTC and voice pack installs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AndroidVoiceEnginesTest {
    private val gib = 1L shl 30
    private val temp = Files.createTempDirectory("aeronex-voice").toFile()
    private val importRoot = File(temp, "external/import")
    private val models = File(temp, "models")
    private val grammar = GrammarAssets.load { File("src/main/assets/$it").inputStream() }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    /** Each file's bytes are its pack id and name; its URL names both, so the fake server knows what to send. */
    private fun bytes(packId: String, name: String) = "$packId/$name ".repeat(300).toByteArray()
    private fun file(packId: String, name: String, role: String) = bytes(packId, name).let {
        PackFile(name, role, it.size.toLong(), sha(it), "https://example.test/$packId/$name")
    }

    private fun ctc(language: String): SpeechModelPack {
        val id = "indicconformer-$language-ctc-int8"
        return SpeechModelPack(id, PackEngine.NEMO_CTC, language, false, DeviceTier.M, null, "MIT", true, 1, true,
            listOf(file(id, "model.int8.onnx", "model"), file(id, "tokens.txt", "tokens")))
    }

    private val kws = "kws-gigaspeech-3.3M-chunk-16".let { id ->
        SpeechModelPack(id, PackEngine.KWS, "en", true, DeviceTier.L, null, "Apache-2.0", true, 1, true,
            listOf("encoder", "decoder", "joiner", "tokens").map { file(id, "$it.onnx", it) })
    }
    private val packs = listOf(ctc("te"), ctc("ta"), kws)
    private fun pack(id: String) = packs.single { it.id == id || it.language == id && it.engine == PackEngine.NEMO_CTC }

    /** Installs run when the test says so (the store's installer thread). */
    private val queued = CopyOnWriteArrayList<Runnable>()
    private val store = ModelPackStore(File(temp, "speech-packs"), importRoot, { queued += it }) { target, profile, cancelled, progress ->
        val (packId, name) = profile.url.removePrefix("https://example.test/").split('/')
        target.install(profile, bytes(packId, name).inputStream(), cancelled, progress)
    }
    private val voskReady = mutableSetOf<String>()
    private val voskModels = CopyOnWriteArrayList<VoskGrammarEngineTest.FakeModel>()
    private val ctcLoads = CopyOnWriteArrayList<CtcModelSpec>()
    private val kwsLoads = CopyOnWriteArrayList<KwsModelSpec>()
    private val ctcLoaderTasks = CopyOnWriteArrayList<Runnable>()

    private fun engines(gb: Double) = AndroidVoiceEngines(
        policy = { EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (gb * gib).toLong(), false, 8)) },
        grammar = { grammar },
        packs = { packs },
        packStore = { store },
        voskReady = { voskReady.toSet() },
        voskDirectory = { File(models, VoskModels.profiles.getValue(it).runtimeFile) },
        kwsKeywordsFile = { File(temp, "kws/keywords-${it.id}.txt") },
        openVosk = { VoskGrammarEngineTest.FakeModel().also { voskModels += it } },
        ctcDecoders = { spec ->
            ctcLoads += spec
            object : CtcDecoder {
                override fun decode(samples: FloatArray) = ""
                override fun close() {}
            }
        },
        kwsDecoders = { spec ->
            kwsLoads += spec
            object : KwsDecoder {
                override fun accept(samples: FloatArray): String? = null
                override fun reset() {}
                override fun close() {}
            }
        },
        ctcLoader = { ctcLoaderTasks += it },
    )

    @After fun cleanUp() {
        temp.deleteRecursively()
    }

    private fun runInstalls() {
        while (queued.isNotEmpty()) queued.removeAt(0).run()
    }

    private fun stage(pack: SpeechModelPack, corrupt: Boolean = false) {
        val folder = File(importRoot, pack.id).also { it.mkdirs() }
        for (f in pack.files) File(folder, f.name).writeBytes(bytes(pack.id, f.name).copyOf().also { if (corrupt) it[3] = 'X'.code.toByte() })
    }

    private fun install(vararg installed: SpeechModelPack) = installed.forEach {
        stage(it)
        store.import(it, { false }, {})
    }

    @Test fun tierHTeluguOpensALazyCtcEngineBesideTheVoskTeluguStopSpotter() {
        voskReady += "vosk-te"
        install(pack("te"), kws)
        val opened = engines(8.0).open("te")
        val command = opened.command!!
        assertEquals(EngineKind.SHERPA_CTC, command.kind)
        assertEquals("voice start loads no CTC model", ModelLoad.COLD, command.modelLoad)
        assertTrue(ctcLoads.isEmpty())
        assertEquals(SpotterKind.VOSK_STOP_GRAMMAR, opened.spotterKind)
        assertTrue("Telugu voice STOP", opened.plan!!.nativeVoiceStop)
        assertTrue("no KWS on Tier H Telugu", kwsLoads.isEmpty())
        val vosk = voskModels.single()
        assertEquals("only the stop grammar: no command recognizer", listOf(grammar.voskStopGrammar("te")),
            vosk.recognizers.map { it.grammar })
        command.prewarm()
        ctcLoaderTasks.single().run()
        assertEquals(ModelLoad.READY, command.modelLoad)
        assertEquals(4, ctcLoads.single().numThreads)
        opened.close()
        assertTrue(vosk.closed)
        assertTrue(vosk.recognizers.single().closed)
    }

    @Test fun tierMTeluguUsesTheEnglishKeywordSpotter() {
        voskReady += "vosk-te"
        install(pack("te"), kws)
        val opened = engines(4.0).open("te")
        assertEquals(EngineKind.SHERPA_CTC, opened.command!!.kind)
        assertEquals(SpotterKind.KWS, opened.spotterKind)
        assertFalse(opened.plan!!.nativeVoiceStop)
        assertTrue("no Vosk model on Tier M Telugu CTC", voskModels.isEmpty())
        assertEquals(listOf("▁ST O P @stop"), kwsLoads.single().keywords)
        opened.close()
    }

    @Test fun tierLTeluguStaysOnVoskWithItsPartialTranscript() {
        voskReady += "vosk-te"
        install(pack("te"), kws)
        val opened = engines(2.0).open("te")
        assertEquals(EngineKind.VOSK_GRAMMAR, opened.command!!.kind)
        assertEquals(SpotterKind.VOSK_STOP_GRAMMAR, opened.spotterKind)
        assertEquals(2, voskModels.single().recognizers.size)
        assertEquals("", opened.partial())
        opened.close()
    }

    /** T15 follow-up: the import runs on the store's installer, not the speech thread, and voice says why it waits. */
    @Test fun aWaitingImportRunsOffTheSpeechThreadAndVoiceSaysInstalling() {
        install(kws)
        stage(pack("ta"))
        val engines = engines(8.0)
        val waiting = engines.open("ta")
        assertNull(waiting.command)
        assertEquals(VoiceReason.INSTALLING_PACK, waiting.reason)
        assertEquals(1, queued.size)
        assertFalse(waiting.outdated())
        assertEquals("a second voice start joins the same import", waiting.reason, engines.open("ta").reason)
        assertEquals(1, queued.size)
        runInstalls()
        assertTrue("the pack is in: reopen", waiting.outdated())
        val ready = engines.open("ta")
        assertEquals(EngineKind.SHERPA_CTC, ready.command!!.kind)
        assertEquals(SpotterKind.KWS, ready.spotterKind)
        ready.close()
    }

    @Test fun voiceKeepsWorkingWhileABetterPackInstallsThenAsksToReopen() {
        voskReady += "vosk-te"
        install(kws)
        stage(pack("te"))
        val engines = engines(4.0)
        val vosk = engines.open("te")
        assertEquals(EngineKind.VOSK_GRAMMAR, vosk.command!!.kind)
        assertFalse(vosk.outdated())
        runInstalls()
        assertTrue(vosk.outdated())
        vosk.close()
        assertEquals(EngineKind.SHERPA_CTC, engines.open("te").command!!.kind)
    }

    /** An install that leaves the plan as it is (the KWS on Tier H Telugu) never unloads the CTC model for nothing. */
    @Test fun onlyAnInstallThatChangesThePlanAsksToReopen() {
        voskReady += "vosk-te"
        install(pack("te"))
        val tierH = engines(8.0).open("te")
        val tierM = engines(4.0).open("te")
        assertEquals(EngineKind.SHERPA_CTC, tierH.command!!.kind)
        assertEquals("no KWS yet: Tier M keeps Vosk-te and its voice STOP", EngineKind.VOSK_GRAMMAR, tierM.command!!.kind)
        store.start(kws, ModelPackStore.Install.Source.DOWNLOAD)
        runInstalls()
        assertFalse("Tier H Telugu keeps the Vosk stop grammar", tierH.outdated())
        assertTrue("Tier M Telugu can move to CTC with the English KWS", tierM.outdated())
        tierH.close()
        tierM.close()
    }

    @Test fun aPackThisPhoneCannotUseIsNeverCopied() {
        stage(pack("ta"))
        stage(kws)
        val opened = engines(2.0).open("ta")
        assertEquals(VoiceReason.NEEDS_NEWER_PHONE, opened.reason)
        assertTrue("no 200 MB copy on Tier L", queued.isEmpty())
        assertEquals(ModelPackStore.State.MISSING, store.state(pack("ta")))
    }

    @Test fun aFailedImportIsNotRetriedOnEveryVoiceStart() {
        install(kws)
        stage(pack("ta"), corrupt = true)
        val engines = engines(8.0)
        assertEquals(VoiceReason.INSTALLING_PACK, engines.open("ta").reason)
        runInstalls()
        val after = engines.open("ta")
        assertEquals(VoiceReason.NOT_INSTALLED, after.reason)
        assertTrue(queued.isEmpty())
    }

    @Test fun aModelsScreenDownloadBringsUpAVoicelessSession() {
        install(kws)
        val engines = engines(8.0)
        val none = engines.open("ta")
        assertEquals(VoiceReason.NOT_INSTALLED, none.reason)
        store.start(pack("ta"), ModelPackStore.Install.Source.DOWNLOAD)
        assertTrue("a download started: say so", none.outdated())
        val installing = engines.open("ta")
        assertEquals(VoiceReason.INSTALLING_PACK, installing.reason)
        runInstalls()
        assertTrue(installing.outdated())
        assertEquals(EngineKind.SHERPA_CTC, engines.open("ta").command!!.kind)
    }
}
