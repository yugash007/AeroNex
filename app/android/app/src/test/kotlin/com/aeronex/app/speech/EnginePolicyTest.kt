package com.aeronex.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePolicyTest {
    private val gib = 1L shl 30
    private fun phone(gb: Double, abi64: Boolean = true, go: Boolean = false, cores: Int = 8) =
        DeviceFacts(if (abi64) listOf("arm64-v8a") else emptyList(), (gb * gib).toLong(), go, cores)

    private fun pack(id: String, engine: PackEngine, language: String, minTier: DeviceTier,
                     defaultEnabled: Boolean = true, licenceConfirmed: Boolean = true) =
        SpeechModelPack(id, engine, language, engine == PackEngine.KWS, minTier, null, "test", licenceConfirmed, 1,
            defaultEnabled, listOf(PackFile("model.int8.onnx", "model", 1, "00", null)))

    private val ctc = listOf("te", "ta", "kn", "ml").associateWith {
        pack("indicconformer-$it-ctc-int8", PackEngine.NEMO_CTC, it, DeviceTier.M)
    }
    private val gigaspeech = pack("kws-gigaspeech-3.3M-chunk-16", PackEngine.KWS, "en", DeviceTier.L)
    private val zhEn = pack("kws-zh-en-3M-chunk-8", PackEngine.KWS, "en", DeviceTier.L,
        defaultEnabled = false, licenceConfirmed = false)
    private val everything = AvailableEngines(setOf("vosk-en-in", "vosk-hi", "vosk-te"),
        ctc.values.toList() + zhEn + gigaspeech)

    @Test fun tiersFollowThe64BitAbiAndTheTotalMemoryCutPoints() {
        assertEquals(DeviceTier.L, EnginePolicy(phone(8.0, abi64 = false)).tier)
        assertEquals(DeviceTier.L, EnginePolicy(phone(2.0)).tier)
        assertEquals(DeviceTier.L, EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (3.3 * gib).toLong() - 1, false, 8)).tier)
        assertEquals(DeviceTier.M, EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (3.3 * gib).toLong(), false, 8)).tier)
        assertEquals("a '4 GB' phone reports under 4 GiB", DeviceTier.M, EnginePolicy(phone(3.7)).tier)
        assertEquals(DeviceTier.M, EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (5.5 * gib).toLong() - 1, false, 8)).tier)
        assertEquals(DeviceTier.H, EnginePolicy(DeviceFacts(listOf("arm64-v8a"), (5.5 * gib).toLong(), false, 8)).tier)
        assertEquals(DeviceTier.H, EnginePolicy(phone(12.0)).tier)
    }

    /** Spec 6.1: the Go flag selects the strict memory profile; it does not set the tier. */
    @Test fun theGoFlagIsAStrictProfileNotATier() {
        val go = EnginePolicy(phone(3.7, go = true))
        assertEquals(DeviceTier.M, go.tier)
        assertTrue(go.strictMemoryProfile)
        assertEquals("strict profile: 5 Hz snapshots", 5, go.snapshotHz)
        assertFalse("strict profile: no hands-free", go.handsFreeAllowed)
        assertEquals(1, EnginePolicy(phone(8.0, go = true)).residentLanguagePacks)
        val plain = EnginePolicy(phone(3.7))
        assertFalse(plain.strictMemoryProfile)
        assertEquals(10, plain.snapshotHz)
        assertTrue(plain.handsFreeAllowed)
        assertEquals("the Tier M ta path stays open on a Go phone", PackEngine.NEMO_CTC,
            (go.plan("ta", everything).command as CommandEngineChoice.Ctc).pack.engine)
    }

    @Test fun tierLRunsAtFiveHertzWithoutHandsFreeAndOnePack() {
        val l = EnginePolicy(phone(2.0))
        assertEquals(5, l.snapshotHz)
        assertEquals(200L, l.snapshotPeriodMs)
        assertFalse(l.handsFreeAllowed)
        assertEquals(1, l.residentLanguagePacks)
        assertEquals(2, EnginePolicy(phone(8.0)).residentLanguagePacks)
        assertEquals(100L, EnginePolicy(phone(8.0)).snapshotPeriodMs)
    }

    @Test fun aMissedFirstSessionBenchmarkDowngradesOneTier() {
        val missed = FirstSessionBenchmark(missedTierTargets = true)
        assertEquals(DeviceTier.M, EnginePolicy(phone(8.0), missed).tier)
        assertEquals(DeviceTier.H, EnginePolicy(phone(8.0), missed).classifiedTier)
        assertEquals(DeviceTier.L, EnginePolicy(phone(4.0), missed).tier)
        assertEquals(DeviceTier.L, EnginePolicy(phone(2.0), missed).tier)
    }

    /** Spec 6.2: Tier L 2-4 ASR threads tuned by RTF (start at 2), Tier M/H 4; never more than the cores; KWS 1. */
    @Test fun asrThreadsFollowTheTier() {
        assertEquals(2, EnginePolicy(phone(2.0)).asrThreads)
        assertEquals(3, EnginePolicy(phone(2.0), FirstSessionBenchmark(tunedAsrThreads = 3)).asrThreads)
        assertEquals(4, EnginePolicy(phone(2.0), FirstSessionBenchmark(tunedAsrThreads = 9)).asrThreads)
        assertEquals(4, EnginePolicy(phone(4.0)).asrThreads)
        assertEquals(4, EnginePolicy(phone(8.0)).asrThreads)
        assertEquals(2, EnginePolicy(phone(8.0, cores = 2)).asrThreads)
        assertEquals(1, EnginePolicy(phone(8.0)).kwsThreads)
    }

    /** Spec 4.0/6.2: Tier L loads exactly one ML runtime per session, whatever is installed. */
    @Test fun tierLUsesExactlyOneRuntime() {
        val policies = listOf(EnginePolicy(phone(2.0)), EnginePolicy(phone(8.0, abi64 = false)),
            EnginePolicy(phone(2.0), FirstSessionBenchmark(ctcRtf = 0.1, ctcWithinMemoryCap = true)))
        for (policy in policies) for (language in listOf("en", "hi", "te", "ta", "kn", "ml")) {
            val plan = policy.plan(language, everything)
            assertTrue("$language: ${plan.runtimes}", plan.runtimes.size <= 1)
            if (plan.command != null) assertEquals(1, plan.runtimes.size)
        }
    }

    @Test fun englishAndHindiUseTheResidentVoskModelWithItsStopGrammarOnEveryTier() {
        for (gb in listOf(2.0, 4.0, 8.0)) for ((language, model) in listOf("en" to "vosk-en-in", "hi" to "vosk-hi")) {
            val plan = EnginePolicy(phone(gb)).plan(language, everything)
            assertEquals(CommandEngineChoice.Vosk(model), plan.command)
            assertEquals(StopChoice.VoskStopGrammar, plan.spotter)
            assertEquals(setOf(SpeechRuntime.VOSK), plan.runtimes)
            assertTrue(plan.nativeVoiceStop)
            assertTrue(plan.partialStop)
        }
    }

    /** W1: IndicConformer needs ~300 MiB of heap per language, so ta/kn/ml voice is Tier M/H only by default. */
    @Test fun tamilKannadaMalayalamCtcRunsOnTierMAndHOnly() {
        for (language in listOf("ta", "kn", "ml")) {
            for (gb in listOf(4.0, 8.0)) {
                val plan = EnginePolicy(phone(gb)).plan(language, everything)
                assertEquals(CommandEngineChoice.Ctc(ctc.getValue(language)), plan.command)
                assertEquals("the Apache-2.0 gigaspeech spotter, never the unconfirmed zh-en",
                    StopChoice.Kws(gigaspeech), plan.spotter)
                assertEquals(setOf(SpeechRuntime.SHERPA_ONNX), plan.runtimes)
                assertFalse("English KWS only", plan.nativeVoiceStop)
                assertFalse("non-streaming: no partial-token STOP", plan.partialStop)
            }
            val low = EnginePolicy(phone(2.0)).plan(language, everything)
            assertNull(low.command)
            assertNull(low.spotter)
            assertEquals(EnginePolicy.NEEDS_TIER_M, low.reason)
        }
    }

    @Test fun tierLRunsCtcOnlyWhenTheFirstSessionBenchmarkPassedSpeedAndMemory() {
        fun on(benchmark: FirstSessionBenchmark) = EnginePolicy(phone(2.0), benchmark).plan("ta", everything).command
        assertNull(on(FirstSessionBenchmark(ctcRtf = 0.2, ctcWithinMemoryCap = true)))
        assertNull("RTF alone is not enough: the 6.3 gate includes the memory cap",
            on(FirstSessionBenchmark(ctcRtf = 0.1, ctcWithinMemoryCap = false)))
        assertNotNull(on(FirstSessionBenchmark(ctcRtf = 0.15, ctcWithinMemoryCap = true)))
    }

    /** Spec 4.6: te on Tier L is Vosk-te (streaming, native STOP); on Tier M/H IndicConformer-te when installed. */
    @Test fun teluguPrefersVoskOnTierLAndCtcOnTierMH() {
        assertEquals(CommandEngineChoice.Vosk("vosk-te"), EnginePolicy(phone(2.0)).plan("te", everything).command)
        assertEquals(CommandEngineChoice.Ctc(ctc.getValue("te")), EnginePolicy(phone(4.0)).plan("te", everything).command)
        val voskOnly = AvailableEngines(setOf("vosk-te"), listOf(gigaspeech))
        assertEquals(CommandEngineChoice.Vosk("vosk-te"), EnginePolicy(phone(8.0)).plan("te", voskOnly).command)
        val ctcOnly = AvailableEngines(emptySet(), listOf(ctc.getValue("te"), gigaspeech))
        assertNull("Tier L without Vosk-te and without the gate: no voice",
            EnginePolicy(phone(2.0)).plan("te", ctcOnly).command)
    }

    /**
     * 0.4.0 "Telugu with IndicConformer, optimised": Tier H keeps Vosk-te resident as the streaming stop spotter beside the
     * CTC command engine (native Telugu voice STOP, the one dual-runtime case); Tier M pairs CTC with the English KWS.
     */
    @Test fun teluguOnTierHPairsCtcWithTheVoskTeluguStopSpotter() {
        val plan = EnginePolicy(phone(8.0)).plan("te", everything)
        assertEquals(CommandEngineChoice.Ctc(ctc.getValue("te")), plan.command)
        assertEquals(StopChoice.VoskStopGrammar, plan.spotter)
        assertEquals(setOf(SpeechRuntime.VOSK, SpeechRuntime.SHERPA_ONNX), plan.runtimes)
        assertTrue("Telugu voice STOP", plan.nativeVoiceStop)
        assertFalse("non-streaming command engine: no partial-token STOP", plan.partialStop)
        val noVosk = AvailableEngines(emptySet(), listOf(ctc.getValue("te"), gigaspeech))
        assertEquals("without Vosk-te the English KWS is the spotter", StopChoice.Kws(gigaspeech),
            EnginePolicy(phone(8.0)).plan("te", noVosk).spotter)
    }

    @Test fun teluguOnTierMPairsCtcWithTheEnglishKeywordSpotter() {
        val plan = EnginePolicy(phone(4.0)).plan("te", everything)
        assertEquals(CommandEngineChoice.Ctc(ctc.getValue("te")), plan.command)
        assertEquals(StopChoice.Kws(gigaspeech), plan.spotter)
        assertEquals(setOf(SpeechRuntime.SHERPA_ONNX), plan.runtimes)
        assertFalse("English \"stop\" only", plan.nativeVoiceStop)
        val strictH = EnginePolicy(phone(8.0, go = true)).plan("te", everything)
        assertEquals("the strict memory profile keeps one runtime", StopChoice.Kws(gigaspeech), strictH.spotter)
        assertEquals(1, strictH.runtimes.size)
    }

    /** Safer reading: CTC without any voice spotter would lose the voice STOP that Vosk-te gives, so Vosk-te stays. */
    @Test fun teluguOnTierMKeepsVoskWhenNoKeywordSpotterIsInstalled() {
        val noKws = AvailableEngines(setOf("vosk-te"), listOf(ctc.getValue("te")))
        val plan = EnginePolicy(phone(4.0)).plan("te", noKws)
        assertEquals(CommandEngineChoice.Vosk("vosk-te"), plan.command)
        assertEquals(StopChoice.VoskStopGrammar, plan.spotter)
        assertEquals("Tier H has the Vosk-te spotter", CommandEngineChoice.Ctc(ctc.getValue("te")),
            EnginePolicy(phone(8.0)).plan("te", noKws).command)
    }

    @Test fun teluguOnTierLIsVoskOnlyEvenWhenTheCtcGatePassed() {
        val gate = FirstSessionBenchmark(ctcRtf = 0.1, ctcWithinMemoryCap = true)
        assertEquals(CommandEngineChoice.Vosk("vosk-te"), EnginePolicy(phone(2.0), gate).plan("te", everything).command)
        val ctcOnly = AvailableEngines(emptySet(), listOf(ctc.getValue("te"), gigaspeech))
        val plan = EnginePolicy(phone(2.0), gate).plan("te", ctcOnly)
        assertNull(plan.command)
        assertEquals(EnginePolicy.NOT_INSTALLED, plan.reason)
        assertNotNull("the gate still opens Tamil", EnginePolicy(phone(2.0), gate).plan("ta", ctcOnly + ctc.getValue("ta")).command)
    }

    /** The Models screen's "Not for this phone" reasons and the voice STOP each pack gives. */
    @Test fun eachPackSaysWhetherItRunsOnThisPhoneAndWhichVoiceStopItGives() {
        val l = EnginePolicy(phone(2.0))
        assertEquals(EnginePolicy.PACK_TELUGU_USES_VOSK, l.packReason(ctc.getValue("te")))
        for (language in listOf("ta", "kn", "ml")) assertEquals(EnginePolicy.PACK_NEEDS_TIER_M, l.packReason(ctc.getValue(language)))
        assertEquals(EnginePolicy.PACK_STOP_NOT_USED, l.packReason(gigaspeech))
        assertEquals(EnginePolicy.PACK_NEEDS_TIER_M, EnginePolicy(phone(8.0, abi64 = false)).packReason(ctc.getValue("ta")))
        val gate = EnginePolicy(phone(2.0), FirstSessionBenchmark(ctcRtf = 0.1, ctcWithinMemoryCap = true))
        assertNull(gate.packReason(ctc.getValue("ta")))
        assertNull("the gate's CTC needs the spotter", gate.packReason(gigaspeech))
        assertEquals(EnginePolicy.PACK_TELUGU_USES_VOSK, gate.packReason(ctc.getValue("te")))
        for (policy in listOf(EnginePolicy(phone(4.0)), EnginePolicy(phone(8.0)))) {
            for (pack in ctc.values + gigaspeech) assertNull(pack.id, policy.packReason(pack))
            assertTrue(policy.listed(gigaspeech))
            assertFalse("unconfirmed licence: never offered", policy.listed(zhEn))
            assertEquals(EnginePolicy.PACK_OFF, policy.packReason(zhEn))
            for (language in listOf("ta", "kn", "ml")) {
                assertEquals(EnginePolicy.NOTE_ENGLISH_STOP, policy.packNote(ctc.getValue(language)))
            }
            assertEquals(EnginePolicy.NOTE_STOP_PACK, policy.packNote(gigaspeech))
        }
        assertEquals(EnginePolicy.NOTE_ENGLISH_STOP, EnginePolicy(phone(4.0)).packNote(ctc.getValue("te")))
        assertEquals(EnginePolicy.NOTE_TELUGU_STOP, EnginePolicy(phone(8.0)).packNote(ctc.getValue("te")))
        assertNull("not for this phone: no note", l.packNote(ctc.getValue("ta")))
    }

    private operator fun AvailableEngines.plus(pack: SpeechModelPack) = copy(packs = packs + pack)

    @Test fun missingModelsMeanNoVoiceWithAReason() {
        val none = AvailableEngines(emptySet(), emptyList())
        for (language in listOf("en", "hi", "te", "ta")) {
            val plan = EnginePolicy(phone(8.0)).plan(language, none)
            assertNull(plan.command)
            assertFalse(plan.voice)
            assertEquals(EnginePolicy.NOT_INSTALLED, plan.reason)
        }
        assertEquals(EnginePolicy.UNSUPPORTED, EnginePolicy(phone(8.0)).plan("fr", everything).reason)
    }

    @Test fun aCtcSessionWithoutAKwsPackHasVoiceButNoVoiceSpotter() {
        val plan = EnginePolicy(phone(8.0)).plan("kn", AvailableEngines(emptySet(), listOf(ctc.getValue("kn"), zhEn)))
        assertNotNull(plan.command)
        assertNull("zh-en stays off unless the operator enables it", plan.spotter)
        val optIn = EnginePolicy(phone(8.0), optionalPacks = setOf(zhEn.id))
            .plan("kn", AvailableEngines(emptySet(), listOf(ctc.getValue("kn"), zhEn)))
        assertEquals(StopChoice.Kws(zhEn), optIn.spotter)
    }
}
