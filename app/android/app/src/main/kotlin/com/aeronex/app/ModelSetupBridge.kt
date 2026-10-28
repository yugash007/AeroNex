package com.aeronex.app

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EnginePolicy
import com.aeronex.app.speech.ModelPackStore
import com.aeronex.app.speech.PackEngine
import com.aeronex.app.speech.SpeechModelPack
import com.aeronex.app.speech.SpeechPackManifest
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Models screen's `aeronex/inference` channel (name kept from v0.2.0 for the Dart client): verified Vosk pack
 * status, download and import, plus the remembered v0.2.0 simulator endpoint that Link setup migrates. Speech runs in
 * the session service; nothing here loads native code.
 *
 * Voice packs (0.4.0: the sherpa-onnx IndicConformer packs and the stop spotter, [ModelPackStore]):
 * - `packs` answers `{tier, metered, packs: [{id, kind (ctc | kws), language, bytes, state (ready | missing | partial |
 *   invalid | checking | unreadable), usable, reason (why it is not for this phone), note (the voice STOP it gives),
 *   downloadable, install ({source: download | import, receivedBytes, totalBytes} while one runs), error (the last
 *   failed install)}]}`: the packs the Models screen offers, in catalogue order.
 * - `downloadPack {id}` starts (or joins: single-flight) the HTTPS download, verified by SHA-256 before install, and
 *   answers when it ends (CANCELLED, or PACK_SETUP with the reason). `cancelPack {id}` stops it.
 * Pack installs belong to the store, not to this screen: [cancel] (the activity going to the background) and [close]
 * leave them running, so a new screen shows their progress. Only `cancelPack` stops one.
 */
class ModelSetupBridge(
    private val activity: Activity,
    messenger: BinaryMessenger,
    private val profiles: Map<String, LocalModelProfile> = PROFILES,
    packs: () -> List<SpeechModelPack> = {
        SpeechPackManifest.parse(activity.assets.open(SpeechPackManifest.ASSET).bufferedReader().use { it.readText() })
    },
    packStore: () -> ModelPackStore = { ModelPackStore.forContext(activity) },
    policy: () -> EnginePolicy = { EnginePolicy(DeviceFacts.read(activity)) },
    private val metered: () -> Boolean = {
        activity.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
    },
) {
    private val packs by lazy(packs)
    private val packStore by lazy(packStore)
    private val policy by lazy(policy)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val inventoryWorker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var generation = 0
    private var pendingImport: Pair<String, MethodChannel.Result>? = null
    private val prefs = activity.getSharedPreferences("models", 0)
    private val directory get() = File(activity.filesDir, "models").also { it.mkdirs() }
    private val inventory = LocalModelInventory(directory, profiles)
    @Volatile private var transfer: Map<String, Any>? = null
    // Dotted-quad literal only (at most 15 chars): no hostnames, no leading zeros.
    private val ipv4 = Regex("""((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)""")

    init {
        MethodChannel(messenger, "aeronex/inference").setMethodCallHandler { call, reply ->
            when (call.method) {
                "status" -> inventoryWorker.execute {
                    val states = modelStates()
                    main.post {
                        reply.success(mapOf("models" to states.mapValues { it.value == "ready" },
                            "modelStates" to states, "transfer" to transfer))
                    }
                }
                "simSettings" -> reply.success(simSettings())
                "saveSimSettings" -> {
                    val args = call.arguments as? Map<*, *>
                    val enabled = args?.get("enabled")
                    val host = args?.get("host")
                    val remotePort = args?.get("remotePort")
                    val localPort = args?.get("localPort")
                    if (enabled !is Boolean || host !is String || !ipv4.matches(host) ||
                        remotePort !is Int || remotePort !in 1..65535 || localPort !is Int || localPort !in 1..65535) {
                        reply.error("INVALID_SIM_SETTINGS", "Enter an IPv4 address and ports between 1 and 65535.", null)
                    } else {
                        prefs.edit().putBoolean("sim-enabled", enabled).putString("sim-host", host)
                            .putInt("sim-remote-port", remotePort).putInt("sim-local-port", localPort).apply()
                        reply.success(null)
                    }
                }
                "importModel" -> {
                    val profile = call.argument<String>("profile")
                    if (!profiles.containsKey(profile) || pendingImport != null || busy.get()) {
                        reply.error("IMPORT", "Invalid model or another operation is active.", null)
                    } else {
                        pendingImport = profile!! to reply
                        try {
                            activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), IMPORT_REQUEST)
                        } catch (error: Exception) { pendingImport = null; reply.error("IMPORT", error.message, null) }
                    }
                }
                "downloadModel" -> {
                    val id = call.argument<String>("profile")
                    if (id !in profiles || pendingImport != null) {
                        reply.error("MODEL_SETUP", "Select a model and finish the current import first.", null)
                    } else transferModel(id!!, reply)
                }
                "cancel" -> { cancel(); reply.success(null) }
                "packs" -> inventoryWorker.execute {
                    val status = try {
                        packStatus()
                    } catch (error: Exception) {
                        main.post { reply.error("PACK_SETUP", error.message ?: "Could not check the voice packs.", null) }
                        return@execute
                    }
                    main.post { reply.success(status) }
                }
                "downloadPack" -> downloadPack(call.argument<String>("id"), reply)
                "cancelPack" -> {
                    cancelPack(call.argument<String>("id"))
                    reply.success(null)
                }
                else -> reply.notImplemented()
            }
        }
    }

    private fun modelStates(): Map<String, String> {
        val states = inventory.states()
        return if (states.containsValue("checking")) inventory.states() else states
    }

    // Saved SITL endpoint. Execution opt-in is per connection and never stored.
    private fun simSettings(): Map<String, Any> {
        val stored = prefs.all // Typed reads would throw on a mistyped value.
        return mapOf("enabled" to (stored["sim-enabled"] as? Boolean ?: false),
            "host" to (stored["sim-host"] as? String ?: "192.168.1.100"),
            "remotePort" to (stored["sim-remote-port"] as? Int ?: 14550),
            "localPort" to (stored["sim-local-port"] as? Int ?: 14551))
    }

    fun activityResult(request: Int, result: Int, data: Intent?): Boolean {
        if (request != IMPORT_REQUEST) return false
        val pending = pendingImport ?: return true
        pendingImport = null
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null) { pending.second.error("CANCELLED", "Import cancelled.", null); return true }
        transferModel(pending.first, pending.second, uri)
        return true
    }

    private fun transferModel(id: String, reply: MethodChannel.Result, uri: Uri? = null) {
        if (!busy.compareAndSet(false, true)) {
            reply.error("BUSY", "Another local operation is active.", null)
            return
        }
        val profile = profiles.getValue(id)
        val token = generation
        cancelled.set(false)
        transfer = mapOf("profile" to id, "receivedBytes" to 0L, "totalBytes" to profile.size)
        worker.execute {
            try {
                val store = VerifiedModelStore(directory)
                val progress: (Long) -> Unit = { count ->
                    transfer = mapOf("profile" to id, "receivedBytes" to count, "totalBytes" to profile.size)
                }
                if (uri == null) store.download(profile, { cancelled.get() }, progress)
                else activity.contentResolver.openInputStream(uri)?.use {
                    store.install(profile, it, { cancelled.get() }, progress)
                } ?: error("The selected file could not be opened. Choose it again.")
                inventory.invalidate(id)
                main.post {
                    busy.set(false)
                    transfer = null
                    if (token != generation || cancelled.get()) reply.error("CANCELLED", "Model setup cancelled.", null)
                    else reply.success(null)
                }
            } catch (error: Exception) {
                main.post {
                    busy.set(false)
                    transfer = null
                    if (cancelled.get() || error is CancellationException) reply.error("CANCELLED", "Model setup cancelled.", null)
                    else reply.error("MODEL_SETUP", error.message ?: "Model setup failed. Check your internet connection or import a file.", mapOf("profile" to id))
                }
            }
        }
    }

    private fun offered(id: String?): SpeechModelPack? = packs.firstOrNull { it.id == id && policy.listed(it) }

    private fun packStatus(): Map<String, Any?> = mapOf(
        "tier" to policy.tier.name,
        "metered" to metered(),
        "packs" to packs.filter(policy::listed).map { pack ->
            val reason = policy.packReason(pack)
            val state = packStore.state(pack)
            val failed = packStore.lastInstall(pack)?.takeIf { it.finished && !it.cancelled && it.error != null }
            mapOf(
                "id" to pack.id,
                "kind" to if (pack.engine == PackEngine.KWS) "kws" else "ctc",
                "language" to pack.language,
                "bytes" to pack.bytes,
                "state" to state.name.lowercase(Locale.ROOT),
                "usable" to (reason == null),
                "reason" to reason,
                "note" to policy.packNote(pack),
                "downloadable" to pack.files.all { it.url != null },
                "install" to packStore.installing(pack)?.let {
                    mapOf("source" to it.source.name.lowercase(Locale.ROOT), "receivedBytes" to it.bytes,
                        "totalBytes" to it.total)
                },
                "error" to failed?.error?.message?.takeIf { state != ModelPackStore.State.READY },
            )
        },
    )

    private fun cancelPack(id: String?) {
        offered(id)?.let { packStore.installing(it)?.cancel() }
    }

    private fun downloadPack(id: String?, reply: MethodChannel.Result) {
        val pack = offered(id)
        val refusal = when {
            pack == null -> "Choose a voice pack from the list."
            policy.packReason(pack) != null -> policy.packReason(pack)
            pack.files.any { it.url == null } -> "${pack.id} has no download address: import it instead."
            else -> null
        }
        if (pack == null || refusal != null) {
            reply.error("PACK_SETUP", refusal, null)
            return
        }
        packStore.start(pack, ModelPackStore.Install.Source.DOWNLOAD).whenFinished { install ->
            main.post {
                try {
                    when {
                        install.installed -> reply.success(null)
                        install.cancelled -> reply.error("CANCELLED", "Voice pack download cancelled.", null)
                        else -> reply.error("PACK_SETUP", install.error?.message
                            ?: "The voice pack could not be installed. Check the internet connection.", mapOf("id" to pack.id))
                    }
                } catch (_: RuntimeException) {
                    // The screen that asked is gone (its engine detached); the row shows the outcome instead.
                }
            }
        }
    }

    /** Stops a Vosk transfer (the activity going to the background); voice pack installs keep running. */
    fun cancel() { generation++; cancelled.set(true) }
    fun close() { cancel(); worker.shutdown(); inventoryWorker.shutdown() }

    companion object {
        /** The packs the app offers: Vosk grammar mode for en-IN, te and hi (sherpa packs use [speech.ModelPackStore]). */
        val PROFILES: Map<String, LocalModelProfile> = VoskModels.profiles
        private const val IMPORT_REQUEST = 72
    }
}
