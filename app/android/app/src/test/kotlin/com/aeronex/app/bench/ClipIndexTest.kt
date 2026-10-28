package com.aeronex.app.bench

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ClipIndexTest {
    private val json = """
        {"clips": [
          {"file": "en/takeoff_3_v0.wav", "lang": "en", "id": "takeoff_3", "intent": "takeoff",
           "text": "take off to three metres", "voice": "en-us", "wpm": 160, "seconds": 2.19},
          {"file": "en/land_v0.wav", "lang": "en", "id": "land", "intent": "land", "text": "land", "seconds": 1.212},
          {"file": "hi/land_v0.wav", "lang": "hi", "id": "land", "intent": "land", "text": "लैंड", "seconds": 1.1},
          {"file": "en/stop_v1.wav", "lang": "en", "id": "stop", "intent": "stop", "text": "stop", "seconds": 1.27}
        ]}
    """.trimIndent()

    @Test fun parsesTheEspeakIndex() {
        val clips = ClipIndex.parse(json)
        assertEquals(4, clips.size)
        assertEquals(ClipInfo("en/takeoff_3_v0.wav", "en", "takeoff_3", "takeoff", "take off to three metres", 2.19), clips[0])
        assertEquals("लैंड", clips[2].text)
    }

    @Test fun selectsTheFirstClipsOfALanguageInIndexOrder() {
        val clips = ClipIndex.parse(json)
        assertEquals(listOf("en/takeoff_3_v0.wav", "en/land_v0.wav"), ClipIndex.select(clips, "en", 2).map { it.file })
        assertEquals(3, ClipIndex.select(clips, "en", 10).size)
        assertEquals(emptyList<ClipInfo>(), ClipIndex.select(clips, "ta", 3))
    }

    @Test fun refusesPathsThatLeaveTheClipFolder() {
        for (file in listOf("../x.wav", "/sdcard/x.wav", "en/../../x.wav", "en\\x.wav")) {
            assertThrows(IllegalArgumentException::class.java) {
                ClipIndex.parse("""{"clips": [{"file": "${file.replace("\\", "\\\\")}", "lang": "en", "id": "land",
                    "intent": "land", "text": "land", "seconds": 1.0}]}""")
            }
        }
    }

    @Test fun theToolingIndexParsesWhenPresent() {
        val index = File("../../../../tooling/models/sherpa/clips-espeak/index.json")
        org.junit.Assume.assumeTrue(index.isFile)
        val clips = ClipIndex.parse(index.readText())
        assertEquals(setOf("en", "hi", "te", "ta", "kn", "ml"), clips.map { it.language }.toSet())
        assertEquals(listOf("takeoff_3", "forward_3", "left_2", "land", "stop", "hold", "neg_takeoff"),
            ClipIndex.select(clips, "ta", 7).map { it.id })
    }
}
