package com.urtv.player.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure rules for the Player's own update path (used only when the UrTV home app is not there to do it): which release is newest,
 * which hosts may be contacted, and whether a downloaded file may be installed. No I/O here, so every rule is unit-tested,
 * hostile inputs included.
 */
object SelfUpdate {
    /** Public repository whose «player-<version>» releases carry the signed APK (the mirror UrTV itself uses). */
    const val RELEASES_URL = "https://api.github.com/repos/tshi-dev/UrTV/releases?per_page=20"
    const val TAG_PREFIX = "player-"
    const val APK_ASSET = "urtv-player.apk"
    const val SHA_ASSET = "urtv-player.apk.sha256"
    const val MAX_APK_BYTES = 25L * 1024 * 1024
    const val MAX_JSON_BYTES = 1L * 1024 * 1024
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

    data class Release(val version: String, val apkUrl: String, val shaUrl: String)

    /** "0.9.10" > "0.9.9"; non-numeric parts compare as 0; "v" prefix and "-suffix" ignored. */
    fun versionParts(v: String): List<Int> = v.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.').map { it.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0 }
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = versionParts(candidate); val b = versionParts(installed); val n = maxOf(a.size, b.size)
        for (i in 0 until n) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    private val VERSION = Regex("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * Newest usable «player-<version>» release in the GitHub releases JSON: not a draft, tag well-formed, both assets present, and
     * every asset URL on an allowed host. Anything malformed is skipped (never throws). Returns null if nothing is newer than [installed].
     */
    fun newest(body: String, installed: String): Release? {
        val arr = runCatching { json.parseToJsonElement(body.take(MAX_JSON_BYTES.toInt())) as? JsonArray }.getOrNull() ?: return null
        var best: Release? = null
        for (el in arr.take(50)) {
            val o = el as? JsonObject ?: continue
            val tag = (o["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (!tag.startsWith(TAG_PREFIX)) continue
            if ((o["draft"] as? JsonPrimitive)?.booleanOrNull == true) continue
            val version = tag.removePrefix(TAG_PREFIX)
            if (!VERSION.matches(version)) continue
            val assets = (o["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: continue
            fun url(name: String) = assets.firstOrNull { (it["name"] as? JsonPrimitive)?.content == name }?.get("browser_download_url")?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val apk = url(APK_ASSET) ?: continue; val sha = url(SHA_ASSET) ?: continue
            if (!hostAllowed(apk) || !hostAllowed(sha)) continue
            if (!isNewer(version, installed)) continue
            if (best == null || isNewer(version, best.version)) best = Release(version, apk, sha)
        }
        return best
    }

    private val HEX64 = Regex("[0-9a-fA-F]{64}")
    /** The digest from a published «<hex>  urtv-player.apk» line (first 64-hex word), or null. */
    fun parseSha(text: String): String? = text.take(MAX_SHA_BYTES.toInt()).trim().split(Regex("\\s+")).firstOrNull()?.takeIf { HEX64.matches(it) }?.lowercase()

    enum class Check { Ok, BadHash, WrongPackage, NotNewer, WrongSigner, MultipleSigners, NoSigner }

    /** May the downloaded file be handed to Android's installer? Every condition must hold. */
    fun check(actualSha: String, publishedSha: String?, filePackage: String?, ourPackage: String, fileCode: Long, installedCode: Long,
              signerDigests: List<String>, ourCert: String): Check {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        if (publishedSha == null || norm(actualSha) != norm(publishedSha)) return Check.BadHash
        if (filePackage != ourPackage) return Check.WrongPackage
        if (fileCode <= installedCode) return Check.NotNewer
        if (signerDigests.isEmpty()) return Check.NoSigner
        if (signerDigests.size != 1) return Check.MultipleSigners
        if (norm(ourCert).isEmpty() || norm(signerDigests[0]) != norm(ourCert)) return Check.WrongSigner
        return Check.Ok
    }
}
