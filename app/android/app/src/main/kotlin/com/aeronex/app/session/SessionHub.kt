package com.aeronex.app.session

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.aeronex.app.ai.AiAvailability
import com.aeronex.app.ai.AiDevice
import com.aeronex.app.ai.AiModelStore
import com.aeronex.app.ai.AiModels
import com.aeronex.app.ai.LfmPlanner
import com.aeronex.app.ai.NativeAiEngine
import com.aeronex.app.ai.NeedleToolCaller
import com.aeronex.app.link.TcpTransport
import com.aeronex.app.link.UdpTransport
import com.aeronex.app.speech.DeviceFacts
import com.aeronex.app.speech.EnginePolicy
import java.io.File
import java.util.function.LongSupplier

/**
 * The process-wide [SessionController], shared by [FlightSessionService], [SessionChannel] and the activity's key
 * handling. Created on first use with the application context; it lives as long as the process (a process death is
 * link loss, which the onboard GCS failsafe handles, S7).
 */
object SessionHub {
    @Volatile private var instance: SessionController? = null

    fun controller(context: Context): SessionController = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    /** The controller if it exists yet (e.g. for onStop, which must not create one). */
    fun existing(): SessionController? = instance

    /** For tests: replaces the controller. */
    internal fun install(controller: SessionController?) = synchronized(this) { instance = controller }

    private fun create(context: Context): SessionController {
        val wifi = WifiNetworkBinder(context)
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return SessionController(
            transports = { config -> transport(config, wifi) },
            traceClock = LongSupplier { SystemClock.elapsedRealtimeNanos() },
            // The link threads carry the heartbeat and STOP: above UI rendering, below audio (spec 4.1, S8).
            linkThreadSetup = { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) },
            voice = VoiceConfig(
                capture = AndroidVoiceEngines::capture,
                engines = AndroidVoiceEngines(context),
                // Spec 4.5: the operator selects a language once, and the choice is saved.
                language = preferences.getString(LANGUAGE, null) ?: VoiceConfig.DEFAULT_LANGUAGE,
                saveLanguage = { preferences.edit().putString(LANGUAGE, it).apply() },
                speechThreadSetup = AndroidVoiceEngines::speechThreadSetup,
            ),
            ai = phoneAi(context),
        ).also { controller ->
            // Push-to-talk with voice off: the (running) service adds the microphone type and starts voice.
            controller.microphoneNeeded = { FlightSessionService.addMicrophone(context) }
        }
    }

    /**
     * The phone's AI path (0.4.0, spec A6): arm64 with Tier M/H and both models verified ([AiAvailability]); the
     * planner and Needle share one [NativeAiEngine] and load lazily on first use. Models live in `filesDir/ai-models`
     * (0.2.0 copies in `filesDir/models` move there) and import from `<external files>/import/<id>/`. The AI thread
     * runs at the default priority, below speech, link and STOP.
     */
    private fun phoneAi(context: Context): AiConfig {
        AiModels.adoptLegacy(context.filesDir)
        val store = AiModelStore(AiModels.directory(context.filesDir).also { it.mkdirs() })
        val tier by lazy { EnginePolicy(DeviceFacts.read(context)).tier }
        return AiConfig(
            availability = { AiAvailability.check(AiDevice.current(), tier, modelsReady = store.ready()) },
            runtime = runtime@{
                val engine = NativeAiEngine.load() ?: return@runtime null
                val files = store.readyFiles() ?: return@runtime null
                val threads = AiAvailability.plannerThreads(Runtime.getRuntime().availableProcessors())
                AiRuntime.of(LfmPlanner(engine, files.lfm, threads), NeedleToolCaller(engine, files.needle))
            },
            models = store,
            importRoot = context.getExternalFilesDir(null)?.let { File(it, "import") },
            threadSetup = { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) },
        )
    }

    private const val PREFERENCES = "aeronex_session"
    private const val LANGUAGE = "speechLanguage"

    private fun transport(config: SessionConfig, wifi: WifiNetworkBinder): TransportLease = when (config.type) {
        LinkType.UDP -> {
            wifi.acquire()
            TransportLease(UdpTransport(config.host, config.remotePort, config.localPort,
                bind = { socket -> wifi.bind(socket, config.host) })) { wifi.release() }
        }
        // TcpTransport has no bind hook yet: default routing (a router on the drone's Wi-Fi with internet works).
        LinkType.TCP -> TransportLease(TcpTransport(config.host, config.remotePort))
    }
}
