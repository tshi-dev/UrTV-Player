package com.urtv.player.server

/**
 * Network policy for the media-server feature.
 * - Discovery and cleartext http are limited to private ranges (RFC 1918, loopback, link-local, CGNAT/Tailscale, ULA).
 * - Anything else must be https. A certificate problem is surfaced to the user, never ignored.
 */
object NetSafety {
    fun isPrivateIpv4(a: Int, b: Int): Boolean = when {
        a == 10 -> true
        a == 127 -> true
        a == 172 && b in 16..31 -> true
        a == 192 && b == 168 -> true
        a == 169 && b == 254 -> true
        a == 100 && b in 64..127 -> true
        else -> false
    }

    private val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")
    private val lanSuffixes = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain")

    /** True for hosts that are on the user's own network. A bare name like `nas` or `jellyfin.local` counts as LAN. */
    fun isPrivateHost(hostRaw: String): Boolean {
        val host = hostRaw.trim().trim('[', ']').lowercase().substringBefore('%')
        if (host.isEmpty()) return false
        v4.matchEntire(host)?.let { m ->
            val p = m.groupValues.drop(1).map { it.toInt() }
            if (p.any { it > 255 }) return false
            return isPrivateIpv4(p[0], p[1])
        }
        if (host.contains(':')) {                       // IPv6 literal
            if (host == "::1") return true
            val first = host.substringBefore(':').ifEmpty { "0" }.toIntOrNull(16) ?: return false
            return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80      // fc00::/7, fe80::/10
        }
        if (host == "localhost") return true
        if (!host.contains('.')) return host.all { it.isLetterOrDigit() || it == '-' }   // single-label name
        return lanSuffixes.any { host.endsWith(it) }
    }

    data class Parsed(val scheme: String, val host: String, val port: Int?, val path: String)

    fun parse(url: String): Parsed? {
        val m = Regex("""^(https?)://([^/?#@\s]+)(/[^?#\s]*)?$""", RegexOption.IGNORE_CASE).matchEntire(url.trim()) ?: return null
        val hostPort = m.groupValues[2]
        val (host, port) = if (hostPort.startsWith("[")) {
            val end = hostPort.indexOf(']'); if (end < 0) return null
            hostPort.substring(1, end) to hostPort.substring(end + 1).removePrefix(":").toIntOrNull()
        } else hostPort.substringBeforeLast(':', hostPort) to if (hostPort.contains(':')) hostPort.substringAfterLast(':').toIntOrNull() else null
        if (host.isEmpty() || (port != null && port !in 1..65535)) return null
        if (hostPort.contains(':') && !hostPort.startsWith("[") && port == null) return null
        return Parsed(m.groupValues[1].lowercase(), host, port, (m.groupValues[3].ifEmpty { "" }).trimEnd('/'))
    }

    /** A base URL is acceptable when it is https, or http to a private host. Credentials in the URL are refused. */
    fun isAllowedBase(url: String): Boolean {
        if (url.contains('@') && url.substringAfter("://").substringBefore('/').contains('@')) return false
        val p = parse(url) ?: return false
        return p.scheme == "https" || isPrivateHost(p.host)
    }

    fun normalizeBase(url: String): String? {
        val p = parse(url) ?: return null
        if (!isAllowedBase(url)) return null
        val host = if (p.host.contains(':')) "[${p.host}]" else p.host
        return "${p.scheme}://$host${p.port?.let { ":$it" } ?: ""}${p.path}"
    }

    /** What to try for a typed address, https first. http candidates only for private hosts. */
    fun candidates(input: String): List<String> {
        val t = input.trim().trimEnd('/')
        if (t.isEmpty()) return emptyList()
        if (t.contains("://")) return listOfNotNull(normalizeBase(t))
        val p = parse("https://$t") ?: return emptyList()
        val host = if (p.host.contains(':')) "[${p.host}]" else p.host
        val path = p.path
        val out = mutableListOf<String>()
        if (p.port != null) {
            out += "https://$host:${p.port}$path"
            if (isPrivateHost(p.host)) out += "http://$host:${p.port}$path"
        } else {
            out += "https://$host$path"; out += "https://$host:8920$path"
            if (isPrivateHost(p.host)) out += "http://$host:8096$path"
        }
        return out.mapNotNull { normalizeBase(it) }.distinct()
    }

    /** A redirect may not downgrade https to http, nor leave the private network for a public http host. */
    fun redirectAllowed(from: String, to: String): Boolean {
        val f = parse(from) ?: return false; val t = parse(to) ?: return false
        if (f.scheme == "https" && t.scheme == "http") return false
        return t.scheme == "https" || isPrivateHost(t.host)
    }
}

data class DiscoveredServer(val id: String, val name: String, val baseUrl: String)

/** Jellyfin/Emby UDP discovery: send "who is JellyfinServer?" to port 7359; servers reply with JSON. */
object Discovery {
    const val PORT = 7359
    val PROBES = listOf("who is JellyfinServer?", "who is EmbyServer?")

    /** [senderHost] is the datagram's source address; replies from public addresses are dropped. */
    fun parseReply(data: ByteArray, senderHost: String): DiscoveredServer? {
        if (data.size > 4096 || !NetSafety.isPrivateHost(senderHost)) return null
        val o = parseObj(String(data, Charsets.UTF_8)) ?: return null
        val addr = o.s("Address") ?: return null
        val p = NetSafety.parse(addr) ?: return null
        // The reply came from a private host. If it advertises a public name (an admin set a public address), the LAN
        // address is still reachable: use the sender with the default port, and let the sign-in probe confirm it.
        val base = (if (NetSafety.isPrivateHost(p.host)) NetSafety.normalizeBase(addr) else null)
            ?: NetSafety.normalizeBase("http://${if (senderHost.contains(':')) "[$senderHost]" else senderHost}:8096")
            ?: return null
        val id = o.s("Id")?.takeIf { it.length in 1..64 } ?: return null
        return DiscoveredServer(id, (o.s("Name") ?: p.host).take(80), base)
    }
}
