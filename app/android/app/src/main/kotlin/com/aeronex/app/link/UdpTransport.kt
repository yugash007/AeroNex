package com.aeronex.app.link

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * MAVLink over UDP: SITL through MAVProxy (`--out udpin:…:14550`), Wi-Fi bridges, ELRS Backpack. Sends to
 * [host]:[remotePort] from [localPort] (0 = any free port) and, like v0.2.0, accepts datagrams **only** from
 * [host]:[remotePort]; everything else is dropped and counted in [droppedDatagrams]. Each [read] returns one datagram.
 *
 * [bind] gets the socket before it is bound or used, e.g. for Android's `Network.bindSocket` so that only the
 * MAVLink socket uses the drone's Wi-Fi network (spec 4.13). [localAddress] (default: all interfaces) restricts the
 * local bind. [host] should be an IP literal: a name is resolved in [open].
 */
class UdpTransport(
    private val host: String,
    private val remotePort: Int,
    private val localPort: Int,
    private val bind: (DatagramSocket) -> Unit = {},
    private val localAddress: String? = null,
) : Transport {
    override val kind = TransportKind.UDP
    override val description = "UDP $host:$remotePort local ${localAddress ?: "*"}:$localPort"

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var peer: InetAddress? = null
    @Volatile private var closed = false

    // RX thread only: reused for every datagram so reading allocates nothing of ours.
    private val packet = DatagramPacket(ByteArray(0), 0)
    private var socketTimeoutMs = -1

    /** Datagrams dropped because they did not come from [host]:[remotePort]. */
    @Volatile var droppedDatagrams = 0L
        private set

    init {
        require(remotePort in 1..65535) { "Remote port must be 1..65535" }
        require(localPort in 0..65535) { "Local port must be 0..65535" }
    }

    override fun open() {
        synchronized(this) {
            check(socket == null && !closed) { "A UdpTransport opens once" }
            val address = InetAddress.getByName(host)
            val created = DatagramSocket(null)
            try {
                bind(created)
                created.bind(InetSocketAddress(localAddress?.let(InetAddress::getByName), localPort))
            } catch (e: Throwable) {
                created.close()
                throw e
            }
            peer = address
            socket = created
        }
    }

    override fun write(bytes: ByteArray): Boolean {
        val s = socket ?: return false
        val to = peer ?: return false
        return try {
            s.send(DatagramPacket(bytes, bytes.size, to, remotePort))
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val s = socket ?: return -1
        try {
            if (timeoutMs != socketTimeoutMs) {
                s.soTimeout = timeoutMs.coerceAtLeast(1) // 0 would block forever
                socketTimeoutMs = timeoutMs
            }
            packet.setData(buffer)
            s.receive(packet)
        } catch (_: SocketTimeoutException) {
            return 0
        } catch (_: IOException) {
            if (closed || s.isClosed) return -1
            pause(timeoutMs) // an unexpected error must not turn the RX loop into a busy spin
            return 0
        }
        if (packet.port != remotePort || packet.address != peer) {
            droppedDatagrams++
            return 0
        }
        return packet.length
    }

    override fun close() {
        synchronized(this) { closed = true }
        socket?.close()
    }

    private fun pause(ms: Int) {
        try {
            Thread.sleep(ms.toLong().coerceAtLeast(1))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
