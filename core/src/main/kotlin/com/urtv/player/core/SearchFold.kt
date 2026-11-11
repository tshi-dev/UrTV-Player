package com.urtv.player.core

/**
 * Channel search that survives the wrong keyboard language and Arabic/Latin naming. Pure logic (unit-tested).
 * A name matches a query if ANY of these views of the query is found in ANY view of the name:
 *  1. plain folded text (case, diacritics, alef/ya/ta-marbuta variants);
 *  2. the query as typed on an Arabic keyboard while the person meant Latin keys («ضعقشى» = «quran»);
 *  3. known words in the other script (قرآن ↔ quran, السعودية ↔ saudi ...), from a small alias table;
 *  4. a loose transliteration, compared as consonant skeletons (qrn = quran = قران).
 */
object SearchFold {
    /** Arabic 101 keyboard: the Latin key under each Arabic letter. */
    private val LAYOUT: Map<Char, Char> = mapOf(
        'ض' to 'q', 'ص' to 'w', 'ث' to 'e', 'ق' to 'r', 'ف' to 't', 'غ' to 'y', 'ع' to 'u', 'ه' to 'i', 'خ' to 'o', 'ح' to 'p',
        'ش' to 'a', 'س' to 's', 'ي' to 'd', 'ب' to 'f', 'ل' to 'g', 'ا' to 'h', 'ت' to 'j', 'ن' to 'k', 'م' to 'l', 'ك' to ';',
        'ئ' to 'z', 'ء' to 'x', 'ؤ' to 'c', 'ر' to 'v', 'ى' to 'n', 'ة' to 'm', 'و' to ',', 'ز' to '.',
        'ج' to '[', 'د' to ']', 'ط' to '\'', 'ظ' to '/',
    )

    /** Rough Arabic to Latin letters (consonant skeleton use). */
    private val TRANSLIT: Map<Char, String> = mapOf(
        'ا' to "a", 'ب' to "b", 'ت' to "t", 'ث' to "th", 'ج' to "j", 'ح' to "h", 'خ' to "kh", 'د' to "d", 'ذ' to "dh", 'ر' to "r", 'ز' to "z",
        'س' to "s", 'ش' to "sh", 'ص' to "s", 'ض' to "d", 'ط' to "t", 'ظ' to "z", 'ع' to "a", 'غ' to "gh", 'ف' to "f", 'ق' to "q", 'ك' to "k",
        'ل' to "l", 'م' to "m", 'ن' to "n", 'ه' to "h", 'و' to "w", 'ي' to "y", 'ى' to "a", 'ة' to "h", 'ء' to "", 'ئ' to "y", 'ؤ' to "w",
    )

    /** Same word in both scripts (normalised forms, see [fold]). Groups mean "any of these". */
    private val ALIASES: List<List<String>> = listOf(
        listOf("قران", "quran", "koran", "qur an", "alquran"),
        listOf("سعوديه", "saudi", "saudia", "saudiya", "ksa"),
        listOf("جزيره", "jazeera", "jazira", "aljazeera"),
        listOf("اخباريه", "ekhbariya", "ekhbaria", "akhbariya"),
        listOf("اخبار", "news", "akhbar"), listOf("رياضه", "sport", "sports", "riyada"), listOf("اطفال", "kids", "children", "atfal"),
        listOf("افلام", "movies", "movie", "films", "film", "aflam"), listOf("وثايقي", "documentary", "documentaries", "wathaiqi"),
        listOf("عام", "general"), listOf("موسيقى", "موسيقي", "music"), listOf("ديني", "religious", "islamic"), listOf("طبخ", "cooking", "cook"),
        listOf("مسلسلات", "series", "drama", "dramas"), listOf("كويت", "kuwait"), listOf("عمان", "oman"), listOf("مصر", "egypt", "misr"),
        listOf("امارات", "emirates", "uae"), listOf("قطر", "qatar"), listOf("بحرين", "bahrain"), listOf("عراق", "iraq"), listOf("اردن", "jordan"),
        listOf("لبنان", "lebanon"), listOf("مباشر", "live"), listOf("مكه", "makkah", "mecca"), listOf("مدينه", "madinah", "medina"),
    ).map { g -> g.map { fold(it) } }

    /** Lower case; no diacritics/tatweel/punctuation; alef, ya, ta-marbuta, waw-hamza folded; leading «ال» of each word dropped. */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s.lowercase()) {
            val c = when (ch) { 'أ', 'إ', 'آ', 'ٱ' -> 'ا'; 'ى' -> 'ي'; 'ة' -> 'ه'; 'ؤ' -> 'و'; 'ئ' -> 'ي'; else -> ch }
            when {
                c == 'ـ' || c in 'ً'..'ٟ' || c == 'ٰ' -> {}
                c.isLetterOrDigit() -> sb.append(c)
                else -> sb.append(' ')
            }
        }
        return sb.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { w -> if (w.length > 3 && w.startsWith("ال")) w.substring(2) else w }
    }

    private fun hasArabic(s: String) = s.any { it in '؀'..'ۿ' }

    /** The Latin keys the person's fingers hit if every Arabic letter was typed on an Arabic keyboard; null if nothing Arabic. */
    fun layoutToLatin(s: String): String? = if (!hasArabic(s)) null else s.map { ch -> LAYOUT[ch] ?: LAYOUT[when (ch) { 'أ', 'إ', 'آ' -> 'ا'; else -> ch }] ?: ch }.joinToString("")

    /** Which script the person means to type, whatever the TV keyboard actually produces. */
    enum class KeyMode { Auto, Latin, Arabic }

    private val REVERSE: Map<Char, Char> = LAYOUT.entries.filter { it.value in 'a'..'z' }.associate { it.value to it.key }

    /** Latin mode: Arabic letters typed on the Arabic keyboard become the Latin keys at the same positions (ضعقشى -> quran), live, as typed. */
    fun arabicKeysToLatin(s: String): String = s.map { LAYOUT[it] ?: LAYOUT[when (it) { 'أ', 'إ', 'آ' -> 'ا'; else -> it }] ?: it }.joinToString("")

    /** Arabic mode: Latin letters typed on a Latin keyboard become the Arabic letters at the same positions (quran -> ضعقشى is NOT wanted; this is for the reverse slip). */
    fun latinKeysToArabic(s: String): String = s.map { REVERSE[it.lowercaseChar()] ?: it }.joinToString("")

    fun applyMode(text: String, mode: KeyMode): String = when (mode) { KeyMode.Auto -> text; KeyMode.Latin -> arabicKeysToLatin(text); KeyMode.Arabic -> latinKeysToArabic(text) }

    fun translit(s: String): String = fold(s).map { TRANSLIT[it] ?: it.toString() }.joinToString("")

    /** Latin consonants only: "quran", "qran" and "قران" all give "qrn". */
    fun skeleton(s: String): String = s.filter { it.isLetter() && it !in "aeiouy" }

    /** Alternative spellings of [word] from the alias table (excluding itself). */
    private fun aliasesOf(word: String): List<String> = ALIASES.firstOrNull { word in it }?.filter { it != word }.orEmpty()

    /** Everything worth looking for in a name for this query (folded). */
    fun queryViews(query: String): List<String> {
        val q = fold(query); if (q.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>(); out += q
        layoutToLatin(query)?.let { out += fold(it) }
        for (v in out.toList()) for (w in v.split(' ')) for (a in aliasesOf(w)) out += v.replace(w, a)
        return out.filter { it.isNotEmpty() }
    }

    /** A query prepared once and applied to many names (20 000 channels). */
    class Query(query: String) {
        private val empty = query.isBlank()
        private val views = if (empty) emptyList() else queryViews(query.trim())
        private val skeletons = views.map { v -> skeleton(if (hasArabic(v)) translit(v) else v) }.filter { it.length >= 3 }.distinct()
        fun matches(name: String): Boolean {
            if (empty) return true
            val n = fold(name)
            if (views.any { n.contains(it) }) return true
            if (skeletons.isEmpty()) return false
            // loose: consonant skeletons (length >= 3 to avoid matching everything)
            val nSkel = skeleton(if (hasArabic(name)) translit(name) else n)
            val nSkelLatin = skeleton(n)
            return skeletons.any { nSkel.contains(it) || nSkelLatin.contains(it) }
        }
    }

    fun matches(name: String, query: String): Boolean = Query(query).matches(name)
}
