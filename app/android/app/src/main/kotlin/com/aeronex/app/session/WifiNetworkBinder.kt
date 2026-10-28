package com.aeronex.app.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Puts only the MAVLink UDP socket on the drone's Wi-Fi network (spec 4.13), so a phone whose default network is
 * mobile data (the drone access point has no internet) still reaches the vehicle, and model downloads stay on the
 * default network. While a UDP session runs, a Wi-Fi network without NET_CAPABILITY_INTERNET is requested, which
 * keeps Android from dropping such a network. A socket is bound only to a Wi-Fi network whose own subnet holds the
 * vehicle address; anything else (loopback, a routed address, no Wi-Fi, an error) keeps default routing.
 */
internal class WifiNetworkBinder(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var users = 0 // guarded by lock
    private var request: ConnectivityManager.NetworkCallback? = null // guarded by lock

    fun acquire() = synchronized(lock) {
        users++
        if (request != null) return@synchronized
        val callback = object : ConnectivityManager.NetworkCallback() {}
        try {
            connectivity.requestNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(), callback)
            request = callback
        } catch (_: RuntimeException) {
            // E.g. a SecurityException: default routing still works on most setups.
        }
    }

    fun release() = synchronized(lock) {
        if (users > 0) users--
        if (users > 0) return@synchronized
        request?.let {
            try {
                connectivity.unregisterNetworkCallback(it)
            } catch (_: RuntimeException) {
                // Already gone.
            }
        }
        request = null
    }

    /** UdpTransport's bind hook: runs on the connecting thread before the local bind. */
    fun bind(socket: DatagramSocket, host: String) {
        val target = try {
            InetAddress.getByName(host)
        } catch (_: IOException) {
            return // UdpTransport.open reports the bad address
        }
        val network = wifiNetworkFor(target) ?: return
        try {
            network.bindSocket(socket)
        } catch (_: IOException) {
            // Default routing.
        }
    }

    @Suppress("DEPRECATION") // allNetworks: the synchronous view; the request's callbacks arrive later
    private fun wifiNetworkFor(host: InetAddress): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            connectivity.getLinkProperties(network)?.linkAddresses
                ?.any { Subnet.contains(it.address, it.prefixLength, host) } == true
    }
}

/** On-link checks for [WifiNetworkBinder]; pure JVM. */
internal object Subnet {
    /** [host] is in the [prefixLength]-bit subnet of [interfaceAddress] (same family; loopback never is). */
    fun contains(interfaceAddress: InetAddress, prefixLength: Int, host: InetAddress): Boolean {
        if (host.isLoopbackAddress) return false
        val a = interfaceAddress.address
        val b = host.address
        if (a.size != b.size || prefixLength <= 0 || prefixLength > a.size * 8) return false
        val whole = prefixLength / 8
        for (i in 0 until whole) if (a[i] != b[i]) return false
        val rest = prefixLength % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
    }
}
