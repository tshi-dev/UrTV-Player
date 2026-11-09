package com.urtv.player.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Is this the official build? Compares the certificate the app is installed with against the SHA-256 pinned at build time
 * (from CI, never in the repository). A repackaged or modified build carries another certificate: it keeps working as a
 * player but shows an "unofficial" notice and turns off the LAN pairing page and backup restore. It never phones home.
 * (Android itself refuses to update an installed official app with a build signed by another key.)
 */
object Integrity {
    enum class Status { Official, Debug, Unofficial }

    fun normalize(hex: String) = hex.filter { it.isLetterOrDigit() }.lowercase()

    /** Pure decision (unit-tested). Official only when a pin exists and EVERY current signer matches it. */
    fun evaluate(pinned: String, installed: Collection<String>, debugBuild: Boolean): Status {
        if (debugBuild) return Status.Debug
        val pin = normalize(pinned)
        if (pin.isEmpty() || installed.isEmpty()) return Status.Unofficial
        return if (installed.all { normalize(it) == pin }) Status.Official else Status.Unofficial
    }

    /** SHA-256 (hex) of each certificate the package is signed with. Cheap, but call off the main thread. */
    @Suppress("DEPRECATION")
    fun installedCertDigests(ctx: Context): List<String> = runCatching {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (if (info?.hasMultipleSigners() == true) info.apkContentsSigners else info?.signingCertificateHistory?.takeLast(1)?.toTypedArray()).orEmpty().toList()
        } else pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty().toList()
        sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    }.getOrDefault(emptyList())

    fun check(ctx: Context): Status = evaluate(com.urtv.player.BuildConfig.OFFICIAL_CERT_SHA256, installedCertDigests(ctx), com.urtv.player.BuildConfig.DEBUG)

    /**
     * Which apps may call the exported FIND_MATCH activity: the UrTV home app or its screensaver, signed by exactly one
     * certificate that equals UrTV's release certificate. [URTV_CERT_SHA256] stays empty (nobody is accepted) until the real
     * digest is supplied by the UrTV owner; it is a public fingerprint, never a key.
     */
    const val URTV_CERT_SHA256 = ""
    val URTV_PACKAGES = setOf("sa.urtv.home", "sa.urtv.screensaver")

    fun callerAllowed(pkg: String?, signers: Collection<String>, pin: String = URTV_CERT_SHA256): Boolean {
        val p = normalize(pin)
        if (pkg == null || pkg !in URTV_PACKAGES || p.isEmpty() || signers.size != 1) return false
        return normalize(signers.single()) == p
    }

    @Suppress("DEPRECATION")
    fun certDigestsOf(ctx: Context, pkg: String): List<String> = runCatching {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            (if (info?.hasMultipleSigners() == true) info.apkContentsSigners else info?.signingCertificateHistory?.takeLast(1)?.toTypedArray()).orEmpty().toList()
        } else pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures.orEmpty().toList()
        sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }
    }.getOrDefault(emptyList())
}
