package com.urtv.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The performance and error log must be easy to find: a row in «عام» and one in «عن التطبيق» (a tester could not find it before). */
class PerfLogRowTest {
    private val src = File("src/main/kotlin/com/urtv/player/ui/Settings.kt").readText()
    private fun block(start: String, end: String) = src.substringAfter(start).substringBefore(end)

    @Test fun theLogHasARowInGeneralAndInAbout() {
        assertTrue("perf_log_title" in block("Section.General -> {", "Section.Playlists ->"), "row in «عام»")
        assertTrue("perf_log_title" in block("Section.About -> item {", "\n            }\n        }\n    }"), "row in «عن التطبيق»")
    }
}
