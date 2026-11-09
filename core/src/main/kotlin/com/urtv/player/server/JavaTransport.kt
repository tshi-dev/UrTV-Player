package com.urtv.player.server

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * The real HTTP seam. Rules: only https, or http to a private host; redirects may not downgrade or leave the LAN;
 * body size and time are capped; a certificate the system does not trust is an error carrying its SHA-256 fingerprint,
 * and is accepted only if that exact fingerprint was approved by the user earlier ([pinnedSha256]).
 */
class JavaTransport(private val pinnedSha256: String? = null, private val maxBytes: Long = 32L * 1024 * 1024, private val timeoutMs: Int = 15000) : Transport {

    private class Seen { @Volatile var fingerprint: String? = null }

    private fun sslFor(seen: Seen): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val fp = fingerprint(chain[0]); seen.fingerprint = fp
                try { system.checkServerTrusted(chain, authType) }
                catch (e: CertificateException) { if (pinnedSha256 == null || !pinnedSha256.equals(fp, true)) throw e }
            }
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
    }

    override fun send(req: Req): Resp {
        var url = req.url
        var method = req.method
        var body = req.body
        repeat(6) {
            val parsed = NetSafety.parse(url)
            if (parsed == null || !NetSafety.isAllowedBase("${parsed.scheme}://${if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host}")) throw ServerException(ServerException.Reason.Refused, "Refusing a non-private http or invalid address")
            val seen = Seen()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                if (c is HttpsURLConnection) {
                    c.sslSocketFactory = sslFor(seen).socketFactory
                    if (pinnedSha256 != null) c.hostnameVerifier = HostnameVerifier { _, session ->
                        // A pinned certificate may be self-signed for an IP; the pin itself identifies the server.
                        runCatching { fingerprint(session.peerCertificates[0] as X509Certificate).equals(pinnedSha256, true) }.getOrDefault(false) ||
                            HttpsURLConnection.getDefaultHostnameVerifier().verify(parsed.host, session)
                    }
                }
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs * 2; c.instanceFollowRedirects = false
                c.requestMethod = method
                req.headers.forEach { (k, v) -> if (k.none { ch -> ch == '\r' || ch == '\n' } && v.none { ch -> ch == '\r' || ch == '\n' }) c.setRequestProperty(k, v) }
                c.setRequestProperty("User-Agent", "UrTV/0.1")
                if (method == "POST" || method == "PUT") {
                    c.doOutput = true
                    val bytes = (body ?: "").toByteArray(Charsets.UTF_8)
                    c.setFixedLengthStreamingMode(bytes.size)
                    c.outputStream.use { it.write(bytes) }
                }
                val code = c.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location") ?: throw ServerException(ServerException.Reason.Http, "Redirect without target")
                    val next = URL(URL(url), loc).toString()
                    if (!NetSafety.redirectAllowed(url, next)) throw ServerException(ServerException.Reason.Refused, "Refusing an insecure redirect")
                    url = next
                    if (code == 301 || code == 302 || code == 303) { method = "GET"; body = null }
                    return@repeat
                }
                val stream = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.use { readCapped(it) } ?: ""
                return Resp(code, text)
            } catch (e: ServerException) { throw e
            } catch (e: SSLException) {
                val fp = seen.fingerprint
                throw ServerException(if (fp != null) ServerException.Reason.Certificate else ServerException.Reason.Network,
                    if (fp != null) "The server's certificate is not trusted" else "Secure connection failed", fp)
            } catch (e: IOException) {
                throw ServerException(ServerException.Reason.Network, "Cannot reach the server")
            } finally { c.disconnect() }
        }
        throw ServerException(ServerException.Reason.Http, "Too many redirects")
    }

    private fun readCapped(i: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024); var total = 0L
        while (true) {
            val n = i.read(buf); if (n < 0) break
            total += n; if (total > maxBytes) throw ServerException(ServerException.Reason.Http, "Response too large")
            out.write(buf, 0, n)
        }
        return out.toString("UTF-8")
    }

    companion object {
        fun fingerprint(c: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it) }
    }
}
