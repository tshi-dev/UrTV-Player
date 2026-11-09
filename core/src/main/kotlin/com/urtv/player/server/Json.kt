package com.urtv.player.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Everything a media server sends is untrusted. These accessors never throw, cap string and list sizes, and treat any
 * wrong-shaped value as absent, so a hostile or buggy server can at worst produce an empty screen.
 */
internal const val MAX_STR = 4000
internal const val MAX_LIST = 5000

internal val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseJson(s: String?): JsonElement? =
    if (s.isNullOrBlank()) null else runCatching { lenientJson.parseToJsonElement(s) }.getOrNull()

internal fun parseObj(s: String?): JsonObject? = parseJson(s) as? JsonObject

internal fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.take(MAX_STR)
internal fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d.isFinite() }?.toLong() }
internal fun JsonObject.i(k: String): Int? = l(k)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
internal fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
internal fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: when (it.content) { "1" -> true; "0" -> false; else -> null } }
internal fun JsonObject.o(k: String): JsonObject? = this[k] as? JsonObject
internal fun JsonObject.a(k: String): List<JsonObject> = (this[k] as? JsonArray)?.asSequence()?.mapNotNull { it as? JsonObject }?.take(MAX_LIST)?.toList() ?: emptyList()
internal fun JsonObject.sa(k: String): List<String> =
    (this[k] as? JsonArray)?.asSequence()?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content?.take(MAX_STR) }?.take(MAX_LIST)?.toList() ?: emptyList()

/** Jellyfin/Emby use 100-nanosecond ticks. */
internal fun ticksToMs(t: Long?): Long = if (t == null || t < 0) 0 else t / 10_000
internal fun msToTicks(ms: Long): Long = ms.coerceAtLeast(0) * 10_000

/** Keeps tokens out of anything that could end up in a log, a crash report or a screenshot. */
object Redact {
    private val params = Regex("""(?i)([?&](?:api_key|apikey|x-plex-token|x-emby-token|token|authtoken|pw|password)=)[^&#\s]*""")
    private val header = Regex("""(?i)(Token\s*=\s*")[^"]*(")""")
    fun url(u: String): String = params.replace(u) { "${it.groupValues[1]}***" }
    fun text(t: String): String = header.replace(url(t)) { "${it.groupValues[1]}***${it.groupValues[2]}" }
}
