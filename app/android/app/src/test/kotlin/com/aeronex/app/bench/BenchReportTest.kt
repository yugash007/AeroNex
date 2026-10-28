package com.aeronex.app.bench

import com.aeronex.app.command.PlanParser
import com.aeronex.app.safety.MiniJson
import com.aeronex.app.speech.EngineKind
import com.aeronex.app.speech.SpeechAlternative
import com.aeronex.app.speech.SpeechResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchReportTest {
    private enum class Colour { RED }

    @Test fun jsonIsValidPrettyAndSafe() {
        val text = BenchJson.write(linkedMapOf("s" to "a\"b\\c\nd\u0001", "n" to null, "t" to true, "i" to 3,
            "l" to 4_000_000_000L, "d" to 1.25, "nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY,
            "e" to Colour.RED, "list" to listOf(1, "x", emptyList<Any>()), "empty" to emptyMap<String, Any>(),
            "telugu" to "టేకాఫ్"))
        assertTrue(text.endsWith("\n"))
        assertTrue("two-space indent", text.contains("\n  \"s\": "))
        @Suppress("UNCHECKED_CAST")
        val back = MiniJson.parse(text) as Map<String, Any?>
        assertEquals("a\"b\\c\nd\u0001", back["s"])
        assertNull(back["n"])
        assertEquals(4_000_000_000L, back["l"])
        assertEquals(1.25, back["d"])
        assertNull("non-finite numbers are null", back["nan"])
        assertNull(back["inf"])
        assertEquals("RED", back["e"])
        assertEquals(listOf(1L, "x", emptyList<Any>()), back["list"])
        assertEquals("టేకాఫ్", back["telugu"])
    }

    @Test fun emulatorsAreRecognised() {
        assertEquals(MeasuredOn.EMULATOR, BenchEnvironment.measuredOn(
            "google/sdk_gphone64_x86_64/emu64xa:15/AE3A.240806.036/12592187:userdebug/dev-keys", "ranchu",
            "sdk_gphone64_x86_64", "sdk_gphone64_x86_64"))
        assertEquals(MeasuredOn.EMULATOR, BenchEnvironment.measuredOn("generic/x/y", "goldfish", "x", "Android SDK built for x86"))
        assertEquals(MeasuredOn.DEVICE, BenchEnvironment.measuredOn(
            "samsung/a03sxx/a03s:13/TP1A.220624.014/A037FXXS7DXA1:user/release-keys", "mt6765", "a03sxx", "SM-A037F"))
    }

    private fun clip(id: String, status: PttStatus?, text: String?, upToFinal: Double?, rtf: Double?, detections: Int?) =
        ClipOutcome(
            clip = ClipInfo("en/$id.wav", "en", id, id, id, 1.5),
            startSample = 0, endSample = 24_000,
            ptt = status?.let {
                PttOutcome(it, text?.let { t -> SpeechResult(EngineKind.VOSK_GRAMMAR, "en", listOf(SpeechAlternative(t, 120.5)), 32_000, 5_000_000) },
                    audioMs = 2000.0, pttUpToDrainedMs = upToFinal?.minus(10), drainedToFinalMs = 10.0, pttUpToFinalMs = upToFinal,
                    parseMs = 1.5, pttUpToParsedMs = upToFinal?.plus(1.5), stopTokenAfterReleaseMs = null,
                    acceptMs = 30.0, finishMs = 5.0, rtf = rtf)
            },
            parsed = text?.let { PlanParser.parseText(it, "en") },
            verdict = status?.let { Expectation.verdict(id, it == PttStatus.STOP_TOKEN, text?.let { t -> PlanParser.parseText(t, "en") }) },
            spotter = detections?.let { SpotterOutcome(it, if (it > 0) 400.0 else null, if (it > 0) -200.0 else null, 600.0, 30.0, 0.05) },
            spotterVerdict = detections?.let { Expectation.spotterVerdict(id, it) },
        )

    private fun memory(label: String, dirty: Long) = MemorySample(label, dirty, 100, dirty + 50_000, emptyMap(), null)

    @Test fun everyEngineAndClipIsMarkedWithWhereItWasMeasured() {
        val run = EngineRun(
            unit = BenchUnit("vosk-en", UnitKind.VOSK, "en", null), engine = "VOSK_GRAMMAR", threads = 1, loadMs = 812.345,
            memory = listOf(memory("before_load", 40_000), memory("after_load", 140_000), memory("after_decode", 150_000)),
            clips = listOf(
                clip("takeoff_3", PttStatus.ENDED, "take off to 3 m", 100.0, 0.02, 0),
                clip("land", PttStatus.ENDED, "land", 120.0, 0.03, 0),
                clip("neg_takeoff", PttStatus.ENDED, "take off to 3 m", 300.0, 0.05, 0),
                clip("stop", PttStatus.STOP_TOKEN, null, null, null, 1),
                clip("hold", PttStatus.ENDED, "land", 140.0, 0.04, 1),
            ),
            probes = mapOf("voskPartialWords" to mapOf("on" to 0)),
        )
        val report = BenchReport.build(MeasuredOn.EMULATOR, environment = mapOf("model" to "sdk_gphone64_x86_64"),
            settings = mapOf("realTime" to true), install = emptyList(), skipped = mapOf("vosk-hi" to BenchPlan.NOT_INSTALLED),
            baseline = memory("baseline", 30_000), runs = listOf(run), startedAt = "s", finishedAt = "f")

        @Suppress("UNCHECKED_CAST")
        val json = MiniJson.parse(BenchJson.write(report)) as Map<String, Any?>
        assertEquals(BenchReport.SCHEMA, json["schema"])
        assertEquals("EMULATOR", json["measuredOn"])
        assertEquals(false, json["representativeOfR0R5"])
        assertTrue((json["note"] as String).contains("not representative"))
        @Suppress("UNCHECKED_CAST")
        val engine = (json["engines"] as List<Map<String, Any?>>).single()
        assertEquals("EMULATOR", engine["measuredOn"])
        assertEquals(812.3, engine["loadMs"])
        @Suppress("UNCHECKED_CAST")
        val clips = engine["clips"] as List<Map<String, Any?>>
        assertTrue(clips.all { it["measuredOn"] == "EMULATOR" })
        assertEquals("take off to 3 m", clips[0]["transcript"])
        assertEquals(listOf(mapOf("name" to "takeoff", "arguments" to mapOf("altitude_m" to 3L))), clips[0]["plan"])
        assertEquals("STOP_TOKEN", clips[3]["status"])

        @Suppress("UNCHECKED_CAST")
        val summary = engine["summary"] as Map<String, Any?>
        assertEquals("EMULATOR", summary["measuredOn"])
        assertEquals(mapOf("EXACT" to 3L, "FALSE_ACCEPT" to 2L, "REJECTED" to 0L, "FALSE_STOP" to 0L), summary["verdicts"])
        assertEquals(mapOf("n" to 4L, "p50Ms" to 120.0, "p95Ms" to 300.0, "maxMs" to 300.0), summary["pttUpToFinalMs"])
        assertEquals(mapOf("n" to 4L, "p50" to 0.03, "p95" to 0.05, "max" to 0.05), summary["rtf"])
        assertEquals(mapOf("stopClips" to 1L, "hits" to 1L, "otherClips" to 4L, "falseFires" to 1L,
            "firstAfterReleaseMs" to mapOf("n" to 1L, "p50Ms" to -200.0, "p95Ms" to -200.0, "maxMs" to -200.0),
            "rtf" to mapOf("n" to 5L, "p50" to 0.05, "p95" to 0.05, "max" to 0.05)), summary["spotter"])
        assertEquals(100_000L, summary["loadPrivateDirtyPlusSwapKb"])
        assertEquals(150_100L, summary["peakPrivateDirtyPlusSwapKb"])
    }

    @Test fun aFailedEngineKeepsItsErrorAndAPhoneIsNotClaimedRepresentative() {
        val run = EngineRun(BenchUnit("ctc-ta", UnitKind.CTC, "ta", "p"), "SHERPA_CTC", 2, null, emptyList(), emptyList(),
            error = "OutOfMemoryError: Failed to allocate")
        val report = BenchReport.build(MeasuredOn.DEVICE, emptyMap(), emptyMap(), emptyList(), emptyMap(), null, listOf(run), "s", "f")
        assertEquals(MeasuredOn.DEVICE, report["measuredOn"])
        assertNull("only the spec 10.1 phones are representative; the harness cannot tell", report["representativeOfR0R5"])
        @Suppress("UNCHECKED_CAST")
        val engine = (report["engines"] as List<Map<String, Any?>>).single()
        assertEquals("OutOfMemoryError: Failed to allocate", engine["error"])
        @Suppress("UNCHECKED_CAST")
        val summary = engine["summary"] as Map<String, Any?>
        assertNull(summary["pttUpToFinalMs"])
        assertFalse(summary.containsKey("spotter"))
    }
}
