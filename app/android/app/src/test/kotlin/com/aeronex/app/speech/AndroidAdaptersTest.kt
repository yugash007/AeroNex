package com.aeronex.app.speech

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.robolectric.RuntimeEnvironment
import com.aeronex.app.audio.AndroidPcmSource
import com.aeronex.app.audio.AudioCapture
import com.aeronex.app.vehicle.executor.waitFor
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/** The Android-facing adapters on Robolectric's framework (no device): facts, pack folders, the microphone source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class AndroidAdaptersTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @After fun clearSource() {
        ShadowAudioRecord.clearSource()
    }

    @Test fun deviceFactsReadMemoryTheGoFlagAndThe64BitAbis() {
        val activities = context.getSystemService(ActivityManager::class.java)
        shadowOf(activities).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = 3L shl 30 })
        shadowOf(activities).setIsLowRamDevice(true)
        val facts = DeviceFacts.read(context)
        assertEquals(3L shl 30, facts.totalMemBytes)
        assertTrue(facts.lowRamDevice)
        assertEquals(Build.SUPPORTED_64_BIT_ABIS.toList(), facts.supported64BitAbis)
        assertTrue(facts.cpuCores >= 1)
    }

    @Test fun packsLiveInFilesDirAndImportFromTheAppExternalFolder() {
        val store = ModelPackStore.forContext(context)
        assertEquals(File(context.filesDir, "speech-packs"), store.root)
        assertEquals(File(context.getExternalFilesDir(null), "import"), store.importRoot)
    }

    /** VOICE_RECOGNITION, 16 kHz mono PCM16, blocking reads feeding the capture loop; the silenced callback registers (API 36). */
    @Test fun theMicrophoneSourceFeedsTheCaptureRing() {
        var reads = 0
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int,
                                          isBlocking: Boolean): Int {
                assertTrue("blocking reads", isBlocking)
                reads++
                for (i in offsetInShorts until offsetInShorts + sizeInShorts) audioData[i] = 1000
                return sizeInShorts
            }
        })
        val capture = AudioCapture(AndroidPcmSource())
        capture.start()
        try {
            waitFor("frames from the shadow microphone", 5_000) { capture.ring.framesWritten >= 10 }
        } finally {
            capture.stop()
        }
        assertTrue(reads >= 10)
        val out = ShortArray(320)
        // The shadow microphone never blocks, so the ring has wrapped: read its newest frame.
        assertEquals(320, capture.ring.read(capture.ring.writePosition - 320, out, 0, out.size))
        assertTrue(out.all { it == 1000.toShort() })
    }
}
