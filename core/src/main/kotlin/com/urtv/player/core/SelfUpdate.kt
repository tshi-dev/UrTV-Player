package com.urtv.player.core


/**
 * Pure rules for the Player's own update path (used only when the UrTV home app is not there to do it): what the version asset announces,
 * which hosts may be contacted, and whether a downloaded file may be installed. No I/O here, so every rule is unit-tested,
 * hostile inputs included.
 */
object SelfUpdate {
    /**
     * One source of truth: the "latest release" of this repository (GitHub never counts pre-releases or drafts as latest). Its stable
     * assets are read through GitHub's fixed redirect, so no API call, no JSON and no rate limit are involved. UrTV reads the same.
     * A version asset holds one line: "<versionCode> <versionName>".
     */
    const val LATEST = "https://github.com/tshi-dev/UrTV-Player/releases/latest/download/"
    const val VERSION_URL = LATEST + "urtv-player.version"
    const val APK_URL = LATEST + "urtv-player.apk"
    const val SHA_URL = LATEST + "urtv-player.apk.sha256"
    const val MAX_APK_BYTES = 25L * 1024 * 1024
    const val MAX_VERSION_BYTES = 256L
    const val MAX_SHA_BYTES = 1024L
    const val MAX_REDIRECTS = 5

    /** The only hosts a release lookup or download may touch: GitHub's API, its web host and its release CDN. https only. */
    val ALLOWED_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "github-releases.githubusercontent.com")

    fun hostAllowed(url: String): Boolean {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!"https".equals(u.scheme, ignoreCase = true) || u.userInfo != null || u.host == null) return false
        if (u.port != -1 && u.port != 443) return false
        return u.host.lowercase() in ALLOWED_HOSTS
    }

    /** What the version asset announced, with the stable download links. [code] is checked again against the downloaded APK. */
    data class Release(val version: String, val code: Long, val apkUrl: String = APK_URL, val shaUrl: String = SHA_URL)

    /** "0.9.10" > "0.9.9"; non-numeric parts compare as 0; "v" prefix and "-suffix" ignored. */
    fun versionParts(v: String): List<Int> = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0 }
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = versionParts(candidate); val b = versionParts(installed); val n = maxOf(a.size, b.size)
        for (i in 0 until n) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    private val VERSION_LINE = Regex("([0-9]{1,9}) ([0-9]{1,6}(\\.[0-9]{1,6}){0,3})")

    /**
     * The release announced by a version asset, only if it is a single well-formed line, its code is higher than [installedCode] and its
     * name is newer than [installedName]. Anything else (garbage, HTML error page, downgrade, same version) gives null.
     */
    fun parseVersion(text: String, installedCode: Long, installedName: String): Release? {
        val m = VERSION_LINE.matchEntire(text.take(MAX_VERSION_BYTES.toInt()).trim()) ?: return null
        val code = m.groupValues[1].toLongOrNull() ?: return null
        val name = m.groupValues[2]
        if (code <= installedCode || !isNewer(name, installedName)) return null
        return Release(name, code)
    }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    /** The digest from a published «<hex>  urtv-player.apk» line (first 64-hex word), or null. */
    fun parseSha(text: String): String? = text.take(MAX_SHA_BYTES.toInt()).trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()

    enum class Check { Ok, BadHash, WrongPackage, NotNewer, WrongVersion, WrongSigner, MultipleSigners, NoSigner }

    /** May the downloaded file be handed to Android's installer? Every condition must hold. */
    fun check(actualSha: String, publishedSha: String?, filePackage: String?, ourPackage: String, fileCode: Long, installedCode: Long,
              signerDigests: List<String>, ourCert: String, expectedCode: Long? = null): Check {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        if (publishedSha == null || norm(actualSha) != norm(publishedSha)) return Check.BadHash
        if (filePackage != ourPackage) return Check.WrongPackage
        if (fileCode <= installedCode) return Check.NotNewer
        if (expectedCode != null && fileCode != expectedCode) return Check.WrongVersion     // not the release that was announced (e.g. "latest" moved mid-way)
        if (signerDigests.isEmpty()) return Check.NoSigner
        if (signerDigests.size != 1) return Check.MultipleSigners
        if (norm(ourCert).isEmpty() || norm(signerDigests[0]) != norm(ourCert)) return Check.WrongSigner
        return Check.Ok
    }
}
