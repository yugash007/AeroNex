package com.aeronex.app.bench

import org.json.JSONObject

/** One entry of the eSpeak clip index (T3 `clips-espeak/index.json`): [file] is relative to the clip folder. */
data class ClipInfo(
    val file: String,
    val language: String,
    /** The phrase id: takeoff_3, forward_3, left_2, land, stop, hold, neg_takeoff ([Expectation] reads it). */
    val id: String,
    val intent: String,
    val text: String,
    val seconds: Double,
)

/** A clip and its 16 kHz mono samples. */
class BenchClip(val info: ClipInfo, val samples: ShortArray)

object ClipIndex {
    const val FILE = "index.json"

    fun parse(json: String): List<ClipInfo> {
        val clips = JSONObject(json).getJSONArray("clips")
        return (0 until clips.length()).map { i ->
            val item = clips.getJSONObject(i)
            val file = item.getString("file")
            require(safe(file)) { "Clip path $file leaves the clip folder" }
            ClipInfo(file, item.getString("lang"), item.getString("id"), item.getString("intent"), item.getString("text"),
                item.getDouble("seconds"))
        }
    }

    /** The first [count] clips of [language] in index order (the index lists voice v0 of every phrase first). */
    fun select(clips: List<ClipInfo>, language: String, count: Int): List<ClipInfo> =
        clips.filter { it.language == language }.take(count)

    private fun safe(path: String) = path.isNotEmpty() && !path.startsWith("/") && '\\' !in path && ':' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
