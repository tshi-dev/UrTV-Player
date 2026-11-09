package com.urtv.player.phone

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

data class PhoneInput(val name: String, val url: String, val server: String, val user: String, val pass: String, val epg: String)

/**
 * One-shot LAN page for adding a playlist from a phone. Local network only, protected by a random
 * 6-digit session code, closes after one successful submit, 5 wrong codes, or 10 minutes.
 */
class PhoneServer(
    private val bind: InetAddress,
    private val onInput: (PhoneInput) -> Unit,
    private val onClosed: () -> Unit,
    private val lifetimeMs: Long = 10 * 60_000L,
) {
    val code: String = "%06d".format(SecureRandom().nextInt(1_000_000))
    @Volatile var port: Int = 0; private set
    @Volatile private var server: ServerSocket? = null
    @Volatile private var done = false
    private var wrong = 0

    /** Host header values this server answers to (defends against DNS rebinding from a browser on the phone). */
    private var hosts: Set<String> = emptySet()

    fun start(): Boolean {
        // Random free port, bound to the LAN address only (not 0.0.0.0), and only while this screen is open.
        try { server = ServerSocket(0, 2, bind); port = server!!.localPort } catch (_: Exception) { return false }
        hosts = setOf("${bind.hostAddress}:$port")
        val s = server ?: return false
        Thread({
            s.soTimeout = 1000
            val deadline = System.currentTimeMillis() + lifetimeMs
            while (!done && System.currentTimeMillis() < deadline) {
                try { handle(s.accept()) } catch (_: SocketTimeoutException) { } catch (_: Exception) { if (done) break }
            }
            close()
        }, "phone-add").apply { isDaemon = true }.start()
        return true
    }

    fun close() {
        if (done && server == null) return
        done = true
        runCatching { server?.close() }; server = null
        onClosed()
    }

    private fun handle(sock: Socket) = sock.use { s ->
        s.soTimeout = 4000
        val addr = s.inetAddress
        val out = s.getOutputStream()
        if (!isLocal(addr)) { respond(out, 403, "text/plain", "forbidden"); return }
        val inp = s.getInputStream()
        val head = readHead(inp) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(' ')
        if (parts.size < 2) return
        val method = parts[0]; val target = parts[1]
        fun header(n: String) = lines.firstOrNull { it.startsWith("$n:", true) }?.substringAfter(':')?.trim()
        if (header("host") !in hosts) { respond(out, 403, "text/plain", "forbidden"); return }
        header("origin")?.let { if (it != "http://${header("host")}") { respond(out, 403, "text/plain", "forbidden"); return } }
        if (method != "GET" && method != "POST") { respond(out, 405, "text/plain", "method not allowed"); return }
        val path = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))
        val len = header("content-length")?.toIntOrNull() ?: 0
        if (len < 0 || len > 8192) { respond(out, 413, "text/plain", "too large"); return }
        val body = if (method == "POST" && len > 0) String(readN(inp, len), Charsets.UTF_8) else ""
        when {
            method == "GET" && path == "/" -> {
                val c = query["c"]
                if (c != null && !checkCode(c)) respond(out, 200, "text/html", Page.codeForm(true))
                else if (c != null) respond(out, 200, "text/html", Page.form(c))
                else respond(out, 200, "text/html", Page.codeForm(false))
            }
            method == "POST" && path == "/add" -> {
                val f = parseForm(body)
                if (!checkCode(f["c"] ?: "")) { respond(out, 403, "text/html", Page.codeForm(true)); return }
                val input = PhoneInput(f["name"].orEmpty().trim().take(60), f["url"].orEmpty().trim(), f["server"].orEmpty().trim(), f["user"].orEmpty().trim(), f["pass"].orEmpty(), f["epg"].orEmpty().trim())
                val ok = input.url.isNotEmpty() || (input.server.isNotEmpty() && input.user.isNotEmpty())
                if (!ok) { respond(out, 400, "text/html", Page.form(f["c"].orEmpty(), error = true)); return }
                respond(out, 200, "text/html", Page.done())
                onInput(input); done = true
            }
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun checkCode(c: String): Boolean {
        val ok = MessageDigest.isEqual(c.toByteArray(), code.toByteArray())
        if (!ok) { Thread.sleep(300); if (++wrong >= 5) done = true }   // slow down guessing, then shut the door
        return ok
    }

    private fun readHead(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        var last = 0
        while (b.size() < 8192) {
            val x = i.read(); if (x < 0) return null
            b.write(x); last = (last shl 8) or x
            if (last == 0x0d0a0d0a) return b.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readN(i: InputStream, n: Int): ByteArray {
        val a = ByteArray(n); var o = 0
        while (o < n) { val r = i.read(a, o, n - o); if (r < 0) break; o += r }
        return a.copyOf(o)
    }

    private fun parseForm(s: String): Map<String, String> = s.split('&').filter { it.contains('=') }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    private fun respond(o: java.io.OutputStream, status: Int, type: String, body: String) {
        val b = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 413 -> "Payload Too Large"; else -> "Error" }
        val h = "HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${b.size}\r\nCache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\nConnection: close\r\n\r\n"
        o.write(h.toByteArray(Charsets.ISO_8859_1)); o.write(b); o.flush()
    }

    companion object {
        private fun isLocal(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)

        /** This device's LAN IPv4 address (site-local only), or null when not on a private network. */
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }
        }.getOrNull()
    }
}
