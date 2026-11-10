package com.urtv.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rules for CI files of a public repository, checked on every run. */
class WorkflowHygieneTest {
    private val files: List<File> by lazy {
        val dir = listOf(".github/workflows", "../.github/workflows").map(::File).first { it.exists() }
        dir.listFiles { f -> f.extension == "yml" }!!.toList()
    }

    @Test fun everyActionIsPinnedToACommitSha() {
        for (f in files) for (l in f.readLines().filter { Regex("""^\s*-?\s*uses:""").containsMatchIn(it) })
            assertTrue(Regex("""uses:\s*[\w./-]+@[0-9a-f]{40}\b""").containsMatchIn(l), "${f.name}: $l")
    }

    @Test fun noPullRequestTargetAndLeastPrivilegeByDefault() {
        for (f in files) {
            val t = f.readLines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")     // comments may mention it
            assertFalse(t.contains("pull_request_target"), f.name)
            assertTrue(Regex("""(?m)^permissions:\s*\n\s+contents:\s*read""").containsMatchIn(t), "${f.name}: top-level read-only permissions")
            assertEquals(1, Regex("""contents:\s*write""").findAll(t).count(), "only the release job may write")
        }
    }

    @Test fun userControlledTextNeverGoesStraightIntoAShellStep() {
        for (f in files) {
            // inside run: blocks, no ${{ inputs.* }}, ${{ github.event.* }}, ${{ github.head_ref }} or ref names: they go through env
            val runs = Regex("""(?s)run:\s*[|>]?-?\s*\n(.*?)(?=\n\s{6}- |\n\s{4}\w|\z)""").findAll(f.readText()).map { it.groupValues[1] }
            for (r in runs) assertFalse(Regex("""\$\{\{\s*(inputs\.|github\.event\.|github\.head_ref|github\.ref_name)""").containsMatchIn(r), "${f.name}: $r")
        }
    }

    @Test fun releaseJobIsOwnerOnlyAndSecretsStayOutOfPullRequests() {
        val t = files.first { it.name == "build.yml" }.readText()
        assertTrue(t.contains("github.repository == 'tshi-dev/UrTV-Player'"))
        assertTrue(t.contains("startsWith(github.ref, 'refs/tags/v')") && t.contains("workflow_dispatch"))
        assertTrue(t.contains("if: \${{ env.FONTS_TOKEN == '' }}"), "debug artifacts are uploaded only when no private font was injected")
        assertTrue(t.contains("retention-days: 3"))
    }
}
