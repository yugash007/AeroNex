package com.aeronex.app.link

import com.aeronex.app.link.mavlink.CommandLong
import com.aeronex.app.link.mavlink.Heartbeat
import com.aeronex.app.link.mavlink.MavlinkEncoder
import com.aeronex.app.link.mavlink.MavlinkFrame
import com.aeronex.app.link.mavlink.MavlinkMessage
import com.aeronex.app.link.mavlink.MavlinkParser
import com.aeronex.app.link.mavlink.Signing
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
internal val key32 = ByteArray(32) { (it * 7 + 3).toByte() }

/** ArduCopter quad heartbeat: GUIDED, disarmed, standby. */
internal val vehicleHeartbeat = Heartbeat(type = 2, autopilot = 3, baseMode = 89, customMode = 4, systemStatus = 3)

internal fun waitFor(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) throw AssertionError("Timed out after $timeoutMs ms waiting for $what")
        Thread.sleep(5)
    }
}

internal fun linkThreadsAlive(description: String) =
    Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("aeronex-link-") && it.name.endsWith("[$description]") }

internal class RecordingListener : LinkListener {
    val frames = CopyOnWriteArrayList<MavlinkFrame>()
    val states = CopyOnWriteArrayList<Boolean>()
    override fun onFrame(frame: MavlinkFrame) { frames += frame }
    override fun onLinkState(up: Boolean) { states += up }
}

internal class Received(val atNanos: Long, val frame: MavlinkFrame)

/**
 * A stand-in for ArduCopter (sysid 1, compid 1): sends HEARTBEAT every [heartbeatMs] while [heartbeats] is set and
 * records every frame the app sends, parsed with its own [MavlinkParser] (and its own [Signing], if given).
 */
internal abstract class FakeVehicle(signing: Signing?, private val heartbeatMs: Long) : AutoCloseable {
    private val encoder = MavlinkEncoder(1, 1, signing)
    protected val parser = MavlinkParser(signing)
    val received = CopyOnWriteArrayList<Received>()
    @Volatile var heartbeats = true
    @Volatile var lastHeartbeatSentAt = 0L
        private set
    @Volatile protected var open = true
    private var beat: Thread? = null

    /** Called by subclasses once their sockets exist. */
    protected fun startHeartbeats() {
        beat = thread(name = "fake-vehicle-heartbeat", isDaemon = true) {
            while (open) {
                if (heartbeats && connected() && writeRaw(encoder.encode(vehicleHeartbeat))) lastHeartbeatSentAt = System.nanoTime()
                try { Thread.sleep(heartbeatMs) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    protected abstract fun connected(): Boolean
    /** Sends raw bytes to the app; false when there is nobody to send to. */
    abstract fun writeRaw(bytes: ByteArray): Boolean

    fun send(message: MavlinkMessage) = writeRaw(encoder.encode(message))

    protected fun record(frames: List<MavlinkFrame>) {
        val now = System.nanoTime()
        for (frame in frames) received += Received(now, frame)
    }

    fun gcsHeartbeats() = received.filter { it.frame.message is Heartbeat && it.frame.sysId == 255 && it.frame.compId == 190 }
    fun commands(command: Int) = received.map { it.frame.message }.filterIsInstance<CommandLong>().filter { it.command == command }
    fun messageIds() = received.map { it.frame.messageId }

    override fun close() {
        open = false
        beat?.interrupt()
    }
}

/** Behaves like MAVProxy `--out udpin:`: replies to whoever sent the last datagram. */
internal class UdpVehicle(signing: Signing? = null, heartbeatMs: Long = 200) : FakeVehicle(signing, heartbeatMs) {
    val socket = DatagramSocket(InetSocketAddress(loopback, 0)).apply { soTimeout = 50 }
    val port: Int get() = socket.localPort
    @Volatile var peer: SocketAddress? = null
    private val reader = thread(name = "fake-vehicle-udp", isDaemon = true) {
        val buffer = ByteArray(4096)
        while (open) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (_: IOException) { break }
            peer = packet.socketAddress
            record(parser.parse(buffer, 0, packet.length))
            parser.reset()
        }
    }

    init { startHeartbeats() }

    override fun connected() = peer != null
    @Synchronized override fun writeRaw(bytes: ByteArray): Boolean {
        val to = peer ?: return false
        return try { socket.send(DatagramPacket(bytes, bytes.size, to)); true } catch (_: IOException) { false }
    }

    override fun close() {
        super.close()
        socket.close()
        reader.join(1_000)
    }
}

/** Behaves like mavlink-router's TCP server: one client at a time; [dropConnection] closes the current one. */
internal class TcpVehicle(signing: Signing? = null, heartbeatMs: Long = 200) : FakeVehicle(signing, heartbeatMs) {
    val server = ServerSocket(0, 4, loopback)
    val port: Int get() = server.localPort
    val accepted = AtomicInteger()
    @Volatile private var connection: Socket? = null
    private val acceptor = thread(name = "fake-vehicle-tcp", isDaemon = true) {
        val buffer = ByteArray(4096)
        while (open) {
            val socket = try { server.accept() } catch (_: IOException) { break }
            accepted.incrementAndGet()
            parser.reset()
            connection = socket
            try {
                val input = socket.getInputStream()
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    record(parser.parse(buffer, 0, n))
                }
            } catch (_: IOException) {
            } finally {
                connection = null
                socket.close()
            }
        }
    }

    init { startHeartbeats() }

    override fun connected() = connection != null
    @Synchronized override fun writeRaw(bytes: ByteArray): Boolean {
        val socket = connection ?: return false
        return try { socket.getOutputStream().write(bytes); true } catch (_: IOException) { false }
    }

    fun dropConnection() { connection?.close() }

    override fun close() {
        super.close()
        server.close()
        connection?.close()
        acceptor.join(1_000)
    }
}

/** Wraps a transport; [write] blocks while the gate is closed, so tests can let the TX queue fill up. */
internal class GatedTransport(private val inner: Transport) : Transport by inner {
    private val gate = CountDownLatch(1)
    val writesStarted = AtomicInteger()
    override fun write(bytes: ByteArray): Boolean {
        writesStarted.incrementAndGet()
        gate.await(10, TimeUnit.SECONDS)
        return inner.write(bytes)
    }
    fun release() = gate.countDown()
}

/** Wraps a transport; each [write] takes [delayMs], like a slow serial radio. */
internal class SlowTransport(private val inner: Transport, private val delayMs: Long) : Transport by inner {
    override fun write(bytes: ByteArray): Boolean {
        Thread.sleep(delayMs)
        return inner.write(bytes)
    }
}

/**
 * An in-memory transport of any [kind] (for the USB kinds, which have no adapter yet). Frames the app writes land in
 * [written]; [inject] feeds bytes to the app's RX thread.
 */
internal class PipeTransport(override val kind: TransportKind) : Transport {
    override val description = "pipe $kind ${System.identityHashCode(this)}"
    val written = LinkedBlockingQueue<ByteArray>()
    private val incoming = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    override fun open() {}
    override fun write(bytes: ByteArray) = !closed && written.add(bytes)
    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) return -1
        val bytes = incoming.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return if (closed) -1 else 0
        bytes.copyInto(buffer)
        return bytes.size
    }
    override fun close() { closed = true }
    fun inject(bytes: ByteArray) { incoming.add(bytes) }
    fun writtenMessages(): List<MavlinkMessage> {
        val parser = MavlinkParser()
        return written.toList().flatMap { parser.parse(it) }.map { it.message }
    }
}
