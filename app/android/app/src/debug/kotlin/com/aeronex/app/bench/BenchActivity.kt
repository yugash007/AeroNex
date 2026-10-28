package com.aeronex.app.bench

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Debug-only Phase 0 benchmark harness (plan T14). It lives in the debug source set, so release APKs never contain
 * it. tools/bench/run_bench.py starts it over adb, for example:
 *
 *     adb shell am start -n com.aeronex.app/.bench.BenchActivity \
 *         --es engines vosk,ctc,kws --es langs en,ta --ei clips 7 [--ei threads 2]
 *
 * It imports the packs pushed to `getExternalFilesDir(null)/import/`, runs every installed engine on the clips pushed
 * to `bench/clips/` (fed at real time through the service's capture ring and PTT tail) and writes
 * `getExternalFilesDir(null)/bench/<timestamp>.json` ([BenchRun]). One run per process at a time.
 */
class BenchActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        log = TextView(this).apply {
            setPadding(32, 120, 32, 32)
            textSize = 12f
        }
        setContentView(ScrollView(this).apply { addView(log) })
        if (intent.getBooleanExtra(PREPARE, false)) {
            // run_bench.py: the app creates its own folders before the shell pushes packs and clips into them.
            val files = getExternalFilesDir(null)
            val ready = files != null && listOf(File(files, "import"), File(files, "bench/clips")).all { it.isDirectory || it.mkdirs() }
            show(if (ready) "Prepared ${files?.path}" else "Could not create the import and bench folders")
            return
        }
        val request = try {
            BenchRequest.parse(intent.getStringExtra(ENGINES), intent.getStringExtra(LANGS), intExtra(CLIPS), intExtra(THREADS))
        } catch (error: IllegalArgumentException) {
            show("Bad extras: ${error.message}")
            return
        }
        synchronized(BenchActivity::class.java) {
            if (running) {
                show("A bench run is already in progress in this process.")
                return
            }
            running = true
        }
        val context = applicationContext
        Thread({
            try {
                BenchRun(context, request, ::show).run()
            } finally {
                running = false
            }
        }, "aeronex-bench").start()
    }

    private fun intExtra(name: String): Int? {
        val extras = intent.extras ?: return null
        if (!extras.containsKey(name)) return null
        @Suppress("DEPRECATION")
        return when (val value = extras.get(name)) {
            is Int -> value
            is Long -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: throw IllegalArgumentException("$name must be a number")
            else -> throw IllegalArgumentException("$name must be a number")
        }
    }

    private fun show(line: String) {
        Log.i(TAG, line)
        runOnUiThread { if (!isDestroyed) log.append(line + "\n") }
    }

    companion object {
        const val TAG = "AeroNexBench"
        const val ENGINES = "engines"
        const val LANGS = "langs"
        const val CLIPS = "clips"
        const val THREADS = "threads"
        const val PREPARE = "prepare"

        @Volatile private var running = false
    }
}
