package com.drift.airmouse.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.coroutines.resume

/**
 * Wear OS sends traffic through the phone over Bluetooth by default, which can't
 * reach a computer on your Wi-Fi. This asks the watch to bring Wi-Fi up and hands
 * back that network so sockets can be bound to it. Call [release] when done.
 */
class WifiLink(context: Context, private val onLost: () -> Unit = {}) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    var network: Network? = null
        private set

    suspend fun acquire(timeoutMs: Long = 15_000): Network? {
        release()
        val found = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(network)
                    }

                    override fun onLost(network: Network) {
                        if (network == this@WifiLink.network) onLost()
                    }
                }
                callback = networkCallback
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                connectivity.requestNetwork(request, networkCallback)
            }
        }
        if (found == null) release() else network = found
        return found
    }

    /** The global broadcast address plus the subnet broadcast of the Wi-Fi network. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf(InetAddress.getByName("255.255.255.255"))
        val links = network?.let { connectivity.getLinkProperties(it) }?.linkAddresses ?: return result
        for (link in links) {
            val address = link.address as? Inet4Address ?: continue
            val ip = ByteBuffer.wrap(address.address).int
            val mask = if (link.prefixLength == 0) 0 else -1 shl (32 - link.prefixLength)
            val broadcast = ByteBuffer.allocate(4).putInt(ip or mask.inv()).array()
            result += InetAddress.getByAddress(broadcast)
        }
        return result
    }

    /**
     * Discovery targets: normal broadcasts plus every IPv4 host on the local subnet.
     * Some Android hotspots/APs suppress broadcast packets between clients, while direct
     * client-to-client UDP still works. Cap to /24 so discovery stays bounded.
     */
    fun discoveryAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        result += broadcastAddresses()

        val links = network?.let { connectivity.getLinkProperties(it) }?.linkAddresses ?: return result.toList()
        for (link in links) {
            val self = link.address as? Inet4Address ?: continue
            val bits = link.prefixLength.coerceIn(24, 30)
            val ip = ByteBuffer.wrap(self.address).int
            val base = ip and (-1 shl (32 - bits))
            val count = (1 shl (32 - bits)) - 2
            for (host in 1..count) {
                val candidate = base + host
                if (candidate == ip) continue
                val bytes = ByteBuffer.allocate(4).putInt(candidate).array()
                result += InetAddress.getByAddress(bytes)
            }
        }
        return result.toList()
    }

    /** Addresses, routes and interface of the Wi-Fi network, once [acquire]d. */
    fun linkProperties(): LinkProperties? = network?.let { connectivity.getLinkProperties(it) }

    fun release() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callback = null
        network = null
    }
}
