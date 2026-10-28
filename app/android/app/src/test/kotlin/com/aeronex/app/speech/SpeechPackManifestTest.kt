package com.aeronex.app.speech

import com.aeronex.app.safety.MiniJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SpeechPackManifestTest {
    private val asset = File("src/main/assets/${SpeechPackManifest.ASSET}")
    private val packs by lazy { SpeechPackManifest.parse(asset.readText()) }

    @Test fun theAssetListsTheFourCtcPacksAndTwoSpotters() {
        assertEquals(listOf("indicconformer-ta-ctc-int8", "indicconformer-kn-ctc-int8", "indicconformer-ml-ctc-int8",
            "indicconformer-te-ctc-int8", "kws-zh-en-3M-chunk-8", "kws-gigaspeech-3.3M-chunk-16"), packs.map { it.id })
        for (pack in packs.filter { it.engine == PackEngine.NEMO_CTC }) {
            assertEquals(DeviceTier.M, pack.minTier)
            assertFalse(pack.streaming)
            assertEquals(listOf("model.int8.onnx", "tokens.txt"), pack.files.map { it.name })
            assertEquals(197_000_000.0, pack.file("model").bytes.toDouble(), 1_000_000.0)
            assertEquals("the shared IndicConformer tokens.txt", 67_605L, pack.file("tokens").bytes)
        }
    }

    @Test fun onlyConfirmedLicencesAreOnByDefault() {
        val zhEn = packs.single { it.id == "kws-zh-en-3M-chunk-8" }
        assertFalse(zhEn.defaultEnabled)
        assertFalse(zhEn.licenceConfirmed)
        assertTrue(zhEn.licence.startsWith("UNCONFIRMED"))
        val gigaspeech = packs.single { it.id == "kws-gigaspeech-3.3M-chunk-16" }
        assertTrue(gigaspeech.defaultEnabled && gigaspeech.licenceConfirmed)
        assertTrue(gigaspeech.licence.startsWith("Apache-2.0"))
        assertEquals(setOf("encoder", "decoder", "joiner", "tokens", "bpe"), gigaspeech.files.map { it.role }.toSet())
        assertEquals(DeviceTier.L, gigaspeech.minTier)
        assertTrue(gigaspeech.streaming)
    }

    /**
     * Every pack the Models screen offers downloads from a revision-pinned HTTPS URL (0.4.0 in-app downloads). GitHub
     * ships the gigaspeech KWS only as a .tar.bz2, so its files come from a Hugging Face copy pinned by commit whose
     * files are byte-identical to that archive's (LFS sha256 and git blob ids checked on 2026-10-15); the store verifies
     * the archive's sha256 on the phone anyway. The unconfirmed zh-en pack stays import only.
     */
    @Test fun offeredPacksDownloadFromRevisionPinnedHttpsUrls() {
        for (pack in packs) for (file in pack.files) {
            when {
                pack.engine == PackEngine.NEMO_CTC -> assertTrue(file.url!!.startsWith(
                    "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/9721eb71eea141fae0982cfcdb9dd2e3d4953c4a/"))
                pack.id == "kws-gigaspeech-3.3M-chunk-16" -> assertEquals(
                    "https://huggingface.co/mobilebytesensei/sherpa-onnx-kws-zipformer-gigaspeech-3.3M/resolve/e8ca74a794ce66e38a100e7c60c0214189ff4100/${file.name}",
                    file.url)
                else -> assertNull("${pack.id}: import only", file.url)
            }
            assertTrue(file.sha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(file.bytes > 0)
        }
    }

    /** The asset must say exactly what T3 downloaded and verified (skipped where the tooling folder is absent). */
    @Test fun everyFileMatchesTheToolingManifest() {
        val tooling = File("../../../../tooling/models/sherpa/manifest.json")
        assumeTrue("tooling manifest present", tooling.isFile)
        @Suppress("UNCHECKED_CAST")
        val root = MiniJson.parse(tooling.readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val entries = ((root["downloads"] as List<Map<String, Any?>>) + (root["extracted"] as List<Map<String, Any?>>))
            .associateBy { it["path"] as String }
        @Suppress("UNCHECKED_CAST")
        val toolingPacks = (root["packs"] as List<Map<String, Any?>>).associateBy { it["id"] as String }
        assertEquals(toolingPacks.keys, packs.map { it.id }.toSet())
        for (pack in packs) {
            @Suppress("UNCHECKED_CAST")
            val paths = toolingPacks.getValue(pack.id)["files"] as List<String>
            assertEquals(paths.map { it.substringAfterLast('/') }, pack.files.map { it.name })
            for ((path, file) in paths.zip(pack.files)) {
                val entry = entries.getValue(path)
                assertEquals(path, (entry["bytes"] as Long), file.bytes)
                assertEquals(path, entry["sha256"], file.sha256)
                // The KWS mirror is not in the tooling manifest (it lists the k2-fsa archive): bytes and sha256 above are.
                if (file.url != null && pack.engine == PackEngine.NEMO_CTC) assertEquals(path, entry["url"], file.url)
            }
        }
    }

    @Test fun aMalformedManifestIsRejected() {
        val good = asset.readText()
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPackManifest.parse(good.replace("\"sha256\": \"", "\"sha256\": \"zz"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPackManifest.parse(good.replace("aeronex-speech-packs/1", "aeronex-speech-packs/2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPackManifest.parse(good.replace("\"name\": \"tokens.txt\"", "\"name\": \"../tokens.txt\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SpeechPackManifest.parse(good.replace("https://huggingface.co", "http://huggingface.co"))
        }
    }
}
