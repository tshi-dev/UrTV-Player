package com.urtv.player.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * LAN discovery for Jellyfin/Emby: a UDP broadcast to port 7359; servers answer the sender directly.
 * Sending a broadcast and receiving unicast replies needs no multicast lock, so no extra permission is requested.
 * Replies from non-private addresses are dropped by [Discovery.parseReply].
 */
object Discover {
    fun scan(timeoutMs: Int = 2500): List<DiscoveredServer> {
        val found = LinkedHashMap<String, DiscoveredServer>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true; sock.soTimeout = 400
                val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
                runCatching {
                    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { ni ->
                        ni.interfaceAddresses.forEach { ia -> val b = ia.broadcast; if (b is Inet4Address && NetSafety.isPrivateHost(b.hostAddress ?: "")) targets += b }
                    }
                }
                for (probe in Discovery.PROBES) for (t in targets) runCatching { val d = probe.toByteArray(); sock.send(DatagramPacket(d, d.size, t, Discovery.PORT)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    Discovery.parseReply(p.data.copyOf(p.length), p.address.hostAddress ?: "")?.let { found.putIfAbsent(it.id, it) }
                }
            }
        }
        return found.values.toList()
    }
}
