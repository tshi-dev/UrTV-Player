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

    @Test fun theTmdbKeyIsOnlyReadByTheReleaseJobAndNeverLogged() {
        val build = files.first { it.name == "build.yml" }.readText()
        assertEquals(1, Regex("""secrets\.TMDB_API_KEY""").findAll(build).count(), "one place gives the secret to a job")
        assertTrue(build.indexOf("secrets.TMDB_API_KEY") > build.indexOf("  release:"), "only the release job, never the pull request test job")
        val src = listOf("src/main", "app/src/main").map(::File).first { it.exists() }
        val client = src.walkTopDown().first { it.name == "TmdbClient.kt" }.readText()
        assertFalse(Regex("""Log\.|println|Diag\.""").containsMatchIn(client), "the client never writes anything that could hold its URL")
        assertTrue(src.walkTopDown().filter { it.extension == "kt" }.none { f -> Regex("""[0-9a-f]{32}""").containsMatchIn(f.readText().lines().filter { it.contains("api_key") }.joinToString()) }, "no key in the sources")
    }

    @Test fun theRollingBetaChannelIsAPreReleaseFilledOnlyByBetaBuildsAndKeptWhenOldReleasesAreCleaned() {
        val w = files.first { it.name == "build.yml" }.readText()
        val step = w.substringAfter("name: Update the rolling beta channel").substringBefore("- name: Keep only the newest release")
        assertTrue("contains(env.TAG, '-')" in step, "only a tag with a suffix (a beta) fills it")
        assertTrue("--prerelease" in step && "--latest" !in step, "never «latest»")
        assertTrue("urtv-player-beta.apk" in step && "urtv-player-beta.apk.sha256" in step && "urtv-player-beta.version" in step && "--clobber" in step)
        assertTrue("secrets." !in step, "the step needs no secret")
        assertTrue("[ \"\$t\" = \"beta\" ] && continue" in w.substringAfter("name: Keep only the newest release"), "a stable release must not delete the beta channel")
    }
}
