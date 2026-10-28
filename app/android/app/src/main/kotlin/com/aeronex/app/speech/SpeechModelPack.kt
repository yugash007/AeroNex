package com.aeronex.app.speech

import com.aeronex.app.LocalModelProfile
import org.json.JSONObject

enum class PackEngine(val id: String) {
    NEMO_CTC("sherpa-onnx-nemo-ctc"),
    KWS("sherpa-onnx-kws"),
}

/** One file of a pack. [url] is a pinned HTTPS URL, or null for import-only files. */
data class PackFile(val name: String, val role: String, val bytes: Long, val sha256: String, val url: String?) {
    /** The verified-store profile of this file ([com.aeronex.app.VerifiedModelStore]). */
    val profile: LocalModelProfile get() = LocalModelProfile(name, bytes, sha256, url)
}

/**
 * An ABI-agnostic speech data pack (spec 4.19): engine, language, the lowest tier it runs on by default, bytes,
 * sha256, min_ram_mb, streaming, licence, grammar_version. Packs are never bundled in the APK; [ModelPackStore]
 * installs them. A pack whose licence is unconfirmed ([licenceConfirmed] false) is never on by default.
 */
data class SpeechModelPack(
    val id: String,
    val engine: PackEngine,
    val language: String,
    val streaming: Boolean,
    val minTier: DeviceTier,
    val minRamMb: Int?,
    val licence: String,
    val licenceConfirmed: Boolean,
    val grammarVersion: Int?,
    val defaultEnabled: Boolean,
    val files: List<PackFile>,
) {
    val bytes: Long get() = files.sumOf { it.bytes }

    fun file(role: String): PackFile = files.single { it.role == role }
}

/** The pack catalogue shipped in the APK: `assets/models/speech_packs.json`, generated from the T3 manifest. */
object SpeechPackManifest {
    const val ASSET = "models/speech_packs.json"
    const val SCHEMA = "aeronex-speech-packs/1"

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ROLES = mapOf(
        PackEngine.NEMO_CTC to setOf("model", "tokens"),
        PackEngine.KWS to setOf("encoder", "decoder", "joiner", "tokens"),
    )

    /** Parses and validates the catalogue; any malformed entry rejects the whole file (IllegalArgumentException). */
    fun parse(json: String): List<SpeechModelPack> {
        val root = JSONObject(json)
        require(root.optString("schema") == SCHEMA) { "Unknown speech pack schema" }
        val packs = root.getJSONArray("packs")
        val parsed = (0 until packs.length()).map { pack(packs.getJSONObject(it)) }
        require(parsed.map { it.id }.toSet().size == parsed.size) { "Duplicate pack id" }
        return parsed
    }

    private fun pack(item: JSONObject): SpeechModelPack {
        val id = item.getString("id")
        require(id.matches(Regex("[A-Za-z0-9._-]+")) && id != "." && id != "..") { "Bad pack id $id" }
        val engine = PackEngine.entries.firstOrNull { it.id == item.getString("engine") }
            ?: throw IllegalArgumentException("Unknown engine in $id")
        val array = item.getJSONArray("files")
        val files = (0 until array.length()).map { file(id, array.getJSONObject(it)) }
        require(files.map { it.name }.toSet().size == files.size) { "Duplicate file in $id" }
        val roles = files.map { it.role }
        require(roles.toSet().size == roles.size && roles.containsAll(ROLES.getValue(engine))) { "Missing roles in $id" }
        val licence = item.getString("licence")
        return SpeechModelPack(
            id = id,
            engine = engine,
            language = item.getString("language"),
            streaming = item.getBoolean("streaming"),
            minTier = DeviceTier.valueOf(item.getString("tier")),
            minRamMb = if (item.isNull("min_ram_mb")) null else item.getInt("min_ram_mb"),
            licence = licence,
            licenceConfirmed = item.getBoolean("licence_confirmed"),
            grammarVersion = if (item.isNull("grammar_version")) null else item.getInt("grammar_version"),
            defaultEnabled = item.getBoolean("default_enabled"),
            files = files,
        ).also { require(!it.defaultEnabled || it.licenceConfirmed) { "$id: an unconfirmed licence cannot be on by default" } }
    }

    private fun file(id: String, item: JSONObject): PackFile {
        val name = item.getString("name")
        require(name.isNotEmpty() && name.none { it == '/' || it == '\\' || it == ':' || it == '\u0000' } &&
            name != "." && name != "..") { "Bad file name in $id" }
        val sha256 = item.getString("sha256")
        require(sha256.matches(SHA256)) { "Bad sha256 for $id/$name" }
        val bytes = item.getLong("bytes")
        require(bytes > 0) { "Bad size for $id/$name" }
        val url = if (item.isNull("url")) null else item.getString("url")
        require(url == null || url.startsWith("https://")) { "Pack downloads must use HTTPS ($id/$name)" }
        return PackFile(name, item.getString("role"), bytes, sha256, url)
    }
}
