package com.urtv.player.core

/** Playlist group-title handling: «Documentary;News» is two groups, and common English group names get an Arabic label. Pure, tested. */
object GroupNames {
    /** A channel's groups: the value split on ; (and |), trimmed, empty parts dropped, duplicates removed. Blank = none. */
    fun split(raw: String): List<String> = raw.split(';', '|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private val AR: Map<String, String> = mapOf(
        "news" to "أخبار", "sports" to "رياضة", "sport" to "رياضة", "kids" to "أطفال", "children" to "أطفال", "cartoon" to "أطفال", "cartoons" to "أطفال",
        "religious" to "ديني", "islamic" to "ديني", "religion" to "ديني", "movies" to "أفلام", "movie" to "أفلام", "films" to "أفلام", "documentary" to "وثائقي",
        "documentaries" to "وثائقي", "cooking" to "طبخ", "general" to "عام", "music" to "موسيقى", "entertainment" to "ترفيه", "series" to "مسلسلات",
        "drama" to "دراما", "lifestyle" to "نمط الحياة", "education" to "تعليم", "educational" to "تعليم", "business" to "أعمال", "weather" to "طقس",
        "comedy" to "كوميديا", "shopping" to "تسوق", "radio" to "راديو", "local" to "محلية", "international" to "دولية", "adult" to "للكبار", "culture" to "ثقافة",
        "science" to "علوم", "travel" to "سفر", "animation" to "رسوم متحركة", "other" to "أخرى", "undefined" to "أخرى", "uncategorized" to "أخرى",
    )

    /** Arabic label when the app is in Arabic and the name is a known English one; otherwise the name as it is. */
    fun display(group: String, arabic: Boolean): String = if (!arabic) group else AR[group.trim().lowercase()] ?: group
}
