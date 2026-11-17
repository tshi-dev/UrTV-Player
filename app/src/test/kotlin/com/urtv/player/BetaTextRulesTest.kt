package com.urtv.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The owner's standing rules for the new beta screens: no «·», no capsules around ratings, versions kept left-to-right, seconds written «ث». */
class BetaTextRulesTest {
    private fun strings(dir: String) = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""").findAll(File("src/main/res/$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
    private val mine = { k: String -> k.startsWith("as_") || k.startsWith("assess_") || k.startsWith("rate_") || k.startsWith("upd_reason") || k.startsWith("upd_check") || k.startsWith("beta_channel") }

    @Test fun noMiddleDotAndNoUnclearUnitInTheBetaTexts() {
        for (dir in listOf("values", "values-en")) for ((k, v) in strings(dir).filterKeys(mine)) {
            assertFalse('·' in v, "$dir/$k has a «·»"); assertFalse("م.ث" in v || "م.ب" in v, "$dir/$k uses an unclear unit")
        }
    }
    @Test fun ratingsArePlainColouredTextNotCapsules() {
        val src = File("src/main/kotlin/com/urtv/player/ui/AssessmentScreen.kt").readText()
        assertFalse("RatingPill" in src); val r = src.substringAfter("private fun RatingText").substringBefore("private fun areaName")
        assertTrue("CircleShape" in r, "a small dot"); assertFalse("Rd(" in r || "RoundedCornerShape" in r || "alpha" in r, "no capsule or tinted background")
    }
    @Test fun versionsAndModelsAreIsolatedLeftToRightInArabicText() {
        val a = File("src/main/kotlin/com/urtv/player/ui/AssessmentScreen.kt").readText(); val s = File("src/main/kotlin/com/urtv/player/ui/Settings.kt").readText()
        assertTrue("fun ltr(" in a && "\\u2066" in a && "\\u2069" in a)
        assertTrue("assess_sub, ltr(" in a && "upd_available, ltr(" in s && "about_version, ltr(" in s)
    }
}
