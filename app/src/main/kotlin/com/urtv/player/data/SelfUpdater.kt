package com.urtv.player.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.urtv.player.core.SelfUpdate
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Player's own update path, used only when the UrTV home app is not installed (or does not answer). It reads the newest
 * «player-<version>» release of the public UrTV repository, downloads the signed APK into app-private storage, and hands it to
 * Android's PackageInstaller after checking: published sha256, our package name, a higher versionCode and a single signer equal to
 * OUR pinned release certificate. Only https to GitHub's own hosts, redirects re-checked hop by hop. Debug and unofficial builds
 * never use it. Every failure is quiet; the installed version keeps working.
 */
class SelfDownloader(
    private val client: OkHttpClient = DEFAULT_CLIENT,
    private val allow: (String) -> Boolean = SelfUpdate::hostAllowed,
) {
    class Refused(msg: String) : IOException(msg)

    /** GET following redirects by hand: each hop must pass [allow] (https and a pinned host) or the whole request is refused. */
    private fun open(url: String, accept: String?): okhttp3.Response {
        var u = url
        repeat(SelfUpdate.MAX_REDIRECTS + 1) {
            if (!allow(u)) throw Refused("host not allowed")
            val r = client.newCall(Request.Builder().url(u).header("User-Agent", "UrTV-Player").apply { accept?.let { header("Accept", it) } }.build()).execute()
            if (r.code in intArrayOf(301, 302, 303, 307, 308)) {
                val loc = r.header("Location"); r.close()
                u = runCatching { URI(u).resolve(loc ?: throw Refused("no location")).toString() }.getOrElse { throw Refused("bad redirect") }
                return@repeat
            }
            if (r.code != 200) { r.close(); throw IOException("http ${r.code}") }
            return r
        }
        throw Refused("too many redirects")
    }

    /** A small text document (release JSON, .sha256), capped. */
    fun text(url: String, maxBytes: Long, accept: String? = null): String = open(url, accept).use { r ->
        val len = r.body?.contentLength() ?: -1
        if (len > maxBytes) throw Refused("too large")
        val b = r.body?.byteStream() ?: throw IOException("empty")
        readCapped(b, maxBytes).toString(Charsets.UTF_8)
    }

    private fun readCapped(i: InputStream, max: Long): ByteArray {
        val o = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var n: Int; var total = 0L
        while (i.read(buf).also { n = it } >= 0) { total += n; if (total > max) throw Refused("too large"); o.write(buf, 0, n) }
        return o.toByteArray()
    }

    /**
     * Streams a file into [dest] through "<dest>.part", counting bytes against [maxBytes] and hashing as it goes. The partial file is
     * removed on any failure (interrupted, too large, disk full) and only a complete download is renamed to [dest]. Returns the sha256 (hex).
     */
    fun file(url: String, dest: File, maxBytes: Long, progress: (done: Long, total: Long) -> Unit = { _, _ -> }, sink: (File) -> OutputStream = { it.outputStream() }): String {
        val part = File(dest.path + ".part"); dest.delete(); part.delete()
        try {
            open(url, null).use { r ->
                val total = r.body?.contentLength() ?: -1
                if (total > maxBytes) throw Refused("too large")
                val md = MessageDigest.getInstance("SHA-256"); var done = 0L
                val inp = r.body?.byteStream() ?: throw IOException("empty")
                sink(part).use { out ->
                    val buf = ByteArray(16 * 1024); var n: Int
                    while (inp.read(buf).also { n = it } >= 0) {
                        done += n; if (done > maxBytes) throw Refused("too large")
                        md.update(buf, 0, n); out.write(buf, 0, n); progress(done, total)
                    }
                    out.flush()
                }
                if (total >= 0 && done != total) throw IOException("interrupted")
                if (!part.renameTo(dest)) throw IOException("rename failed")
                return md.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Throwable) { part.delete(); dest.delete(); throw e }
    }

    companion object {
        val DEFAULT_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
        }
    }
}

object SelfUpdater {
    private const val ACTION = "com.urtv.player.INSTALL_STATUS"
    fun dir(ctx: Context) = File(ctx.cacheDir, "update").apply { mkdirs() }

    /** May this build update itself? Only the official, release-signed build with a pinned certificate. */
    fun allowed(official: Boolean): Boolean = official && !com.urtv.player.BuildConfig.DEBUG && Integrity.normalize(com.urtv.player.BuildConfig.OFFICIAL_CERT_SHA256).isNotEmpty()

    /** Leftovers of an interrupted update (call at start, off the main thread). */
    fun cleanup(ctx: Context) { runCatching { dir(ctx).listFiles()?.forEach { it.delete() } } }

    /** The daily question: newest release newer than this build, or null (also on any failure: quiet, asked again tomorrow). Blocking. */
    fun lookup(installedName: String, dl: SelfDownloader = SelfDownloader()): SelfUpdate.Release? = runCatching {
        SelfUpdate.newest(dl.text(SelfUpdate.RELEASES_URL, SelfUpdate.MAX_JSON_BYTES, "application/vnd.github+json"), installedName)
    }.getOrNull()

    sealed interface Outcome {
        object Handed : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Downloads, verifies and hands the update to Android. Blocking: run off the main thread. [progress] gets bytes done/total.
     * The downloaded file is always deleted afterwards.
     */
    fun run(ctx: Context, rel: SelfUpdate.Release, progress: (Long, Long) -> Unit, dl: SelfDownloader = SelfDownloader()): Outcome {
        val apk = File(dir(ctx), "urtv-player.apk")
        try {
            val published = SelfUpdate.parseSha(dl.text(rel.shaUrl, SelfUpdate.MAX_SHA_BYTES)) ?: return Outcome.Failed("bad sha file")
            val actual = dl.file(rel.apkUrl, apk, SelfUpdate.MAX_APK_BYTES, progress)
            val info = ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = signerDigests(info)
            val code = if (info == null) 0L else if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            val installed = if (Build.VERSION.SDK_INT >= 28) ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode.toLong()
            val c = SelfUpdate.check(actual, published, info?.packageName, ctx.packageName, code, installed, signers, com.urtv.player.BuildConfig.OFFICIAL_CERT_SHA256)
            if (c != SelfUpdate.Check.Ok) return Outcome.Failed("check $c")
            install(ctx, apk)
            return Outcome.Handed
        } catch (e: Throwable) {
            Diag.record(e)
            return Outcome.Failed(e.javaClass.simpleName)
        } finally { if (!installing) apk.delete() }
    }

    @Volatile private var installing = false

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo?): List<String> {
        info ?: return emptyList()
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo
            (if (s?.hasMultipleSigners() == true) s.apkContentsSigners else s?.signingCertificateHistory?.takeLast(1)?.toTypedArray()).orEmpty().toList()
        } else info.signatures.orEmpty().toList()
        return sigs.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
    }

    /** One PackageInstaller session; the system's own confirmation screen follows. The session is written from the private file only. */
    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(params)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> @Suppress("DEPRECATION") (i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    PackageInstaller.STATUS_SUCCESS -> { installing = false; apk.delete(); runCatching { c.unregisterReceiver(this) } }
                    else -> { installing = false; apk.delete(); runCatching { c.unregisterReceiver(this) } }     // failed or cancelled: quiet, the old version keeps working
                }
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, filter)
        installing = true
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { i -> s.openWrite("urtv-player.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
                val pending = PendingIntent.getBroadcast(ctx, id, Intent(ACTION).setPackage(ctx.packageName), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                s.commit(pending.intentSender)
            }
        } catch (e: Throwable) { installing = false; runCatching { ctx.unregisterReceiver(receiver) }; runCatching { pi.abandonSession(id) }; throw e }
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()
}
