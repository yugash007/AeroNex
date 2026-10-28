package com.aeronex.app.session

import com.aeronex.app.link.TcpTransport
import com.aeronex.app.link.TelemetryProfile
import com.aeronex.app.link.Transport
import com.aeronex.app.link.TransportKind
import com.aeronex.app.link.UdpTransport

/** A session call that failed; [code] becomes the PlatformException code on the Dart side, [message] is shown. */
class SessionException(val code: String, message: String) : Exception(message) {
    override val message: String get() = super.message!!

    companion object {
        /** The arguments do not have the agreed shape: a client bug, or a setting the operator must fix. */
        const val BAD_ARGS = "BAD_ARGS"
        const val ALREADY_CONNECTED = "ALREADY_CONNECTED"
        const val CONNECT_FAILED = "CONNECT_FAILED"
    }
}

/** The link types the Link setup screen offers (USB arrives in Phase 2). */
enum class LinkType(val wireName: String, val kind: TransportKind) {
    UDP("udp", TransportKind.UDP),
    TCP("tcp", TransportKind.TCP),
}

/** A transport for one connection plus what to undo after it closes (e.g. an Android Wi-Fi network request). */
class TransportLease(val transport: Transport, private val onRelease: () -> Unit = {}) {
    fun release() = onRelease()
}

/**
 * The `connect` settings from the Dart client: `{transport: 'udp'|'tcp', host, remotePort, localPort, radioLink}`.
 * UDP sends from [localPort] (0 = any free port) to [host]:[remotePort] and accepts datagrams only from there; TCP
 * dials [host]:[remotePort] and ignores [localPort]. [radioLink] says the UDP/TCP link crosses a telemetry radio
 * (ELRS Backpack, a Wi-Fi bridge on a SiK link), so the RADIO telemetry profile and timings apply (spec 4.15).
 */
data class SessionConfig(
    val type: LinkType,
    val host: String,
    val remotePort: Int,
    val localPort: Int = 0,
    val radioLink: Boolean = false,
) {
    init {
        require(host.isNotBlank()) { BAD_HOST }
        require(remotePort in 1..65535) { BAD_REMOTE_PORT }
        require(localPort in 0..65535) { BAD_LOCAL_PORT }
    }

    val profile: TelemetryProfile get() = if (radioLink) TelemetryProfile.RADIO else TelemetryProfile.forKind(type.kind)

    /** For messages: "UDP link to 10.0.0.1:14550". */
    val label: String get() = "${type.name} link to $host:$remotePort"

    companion object {
        const val MISSING = "Connection settings are missing."
        const val BAD_TRANSPORT = "Choose UDP or TCP."
        const val BAD_HOST = "Enter the vehicle's address."
        const val BAD_REMOTE_PORT = "Enter a vehicle port from 1 to 65535."
        const val BAD_LOCAL_PORT = "Enter a local port from 0 to 65535."
        const val BAD_RADIO_LINK = "The telemetry radio setting must be on or off."

        /** Parses the Dart map; a [SessionException] ([SessionException.BAD_ARGS]) carries the operator message. */
        fun fromMap(arguments: Any?): SessionConfig {
            val map = arguments as? Map<*, *> ?: throw bad(MISSING)
            val type = LinkType.entries.firstOrNull { it.wireName == map["transport"] } ?: throw bad(BAD_TRANSPORT)
            val host = (map["host"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: throw bad(BAD_HOST)
            val remotePort = port(map["remotePort"], 1) ?: throw bad(BAD_REMOTE_PORT)
            val localPort = if (map["localPort"] == null) 0 else port(map["localPort"], 0) ?: throw bad(BAD_LOCAL_PORT)
            val radioLink = when (val value = map["radioLink"]) {
                null -> false
                is Boolean -> value
                else -> throw bad(BAD_RADIO_LINK)
            }
            return SessionConfig(type, host, remotePort, localPort, radioLink)
        }

        /** Plain JVM sockets with default routing (host tests, SITL); Android binds UDP to the Wi-Fi network. */
        fun plainTransport(config: SessionConfig): TransportLease = TransportLease(when (config.type) {
            LinkType.UDP -> UdpTransport(config.host, config.remotePort, config.localPort)
            LinkType.TCP -> TcpTransport(config.host, config.remotePort)
        })

        private fun port(value: Any?, min: Int): Int? = when (value) {
            is Int -> value
            is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            else -> null
        }?.takeIf { it in min..65535 }

        private fun bad(message: String) = SessionException(SessionException.BAD_ARGS, message)
    }
}
