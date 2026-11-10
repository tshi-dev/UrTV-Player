package com.urtv.player.core

import com.urtv.player.core.SelfUpdate.Check
import kotlin.test.*

class SelfUpdateTest {
    private val cert = "13ade7d009a07f5ba5bab0b7734bd449df10dd47b53868dfa28e44cd8069dfea"
    private val sha = "a".repeat(64)
    private fun rel(tag: String, draft: Boolean = false, apk: String = "https://github.com/tshi-dev/UrTV/releases/download/$tag/urtv-player.apk", shaUrl: String = "https://github.com/tshi-dev/UrTV/releases/download/$tag/urtv-player.apk.sha256", extra: String = "") =
        """{"tag_name":"$tag","draft":$draft,"prerelease":false$extra,"assets":[{"name":"urtv-player.apk","browser_download_url":"$apk"},{"name":"urtv-player.apk.sha256","browser_download_url":"$shaUrl"},{"name":"notes.txt","browser_download_url":"https://evil.example/x"}]}"""

    @Test fun picksTheNewestPlayerReleaseAndIgnoresOtherTagsAndDrafts() {
        val body = "[" + listOf(rel("home-3.0.0"), rel("player-0.9.10", draft = true), rel("player-0.9.9"), rel("player-0.9.10x"), rel("player-0.9.4")).joinToString(",") + "]"
        assertEquals("0.9.9", SelfUpdate.newest(body, installed = "0.9.3")?.version)
        assertNull(SelfUpdate.newest(body, installed = "0.9.9"), "same version: nothing")
        assertNull(SelfUpdate.newest(body, installed = "1.0.0"), "installed is newer (downgrade is never offered)")
        assertEquals("0.9.11", SelfUpdate.newest("[" + rel("player-0.9.9") + "," + rel("player-0.9.11") + "]", "0.9.10")?.version, "numeric, not text, comparison")
    }

    @Test fun hostileReleaseJsonIsIgnoredNeverThrows() {
        for (b in listOf("", "null", "{}", "[1,2,3]", "[{\"tag_name\":5}]", "[{\"tag_name\":\"player-9.9.9\"}]", "[{\"tag_name\":\"player-9.9.9\",\"assets\":\"x\"}]", "<html>", "[".repeat(5000), "\u0000".repeat(10)))
            assertNull(SelfUpdate.newest(b, "0.9.3"), b.take(20))
        // right shape but the download points elsewhere: skipped
        for (u in listOf("http://github.com/x/urtv-player.apk", "https://evil.example/urtv-player.apk", "https://github.com.evil.example/a", "https://github.com@evil.example/a", "https://github.com:8443/a", "file:///sdcard/a.apk", "javascript:alert(1)"))
            assertNull(SelfUpdate.newest("[" + rel("player-9.9.9", apk = u) + "]", "0.9.3"), u)
        assertNull(SelfUpdate.newest("[" + rel("player-9.9.9", shaUrl = "https://evil.example/s") + "]", "0.9.3"))
        assertNull(SelfUpdate.newest("[" + rel("player-9.9.9; rm -rf") + "]", "0.9.3"))
        assertNull(SelfUpdate.newest("[" + rel("player-../../9") + "]", "0.9.3"))
    }

    @Test fun onlyPinnedGithubHostsOverHttpsAreAllowed() {
        for (u in listOf("https://api.github.com/repos/a/b", "https://github.com/a/b/releases/download/x/y.apk", "https://objects.githubusercontent.com/abc?X-Amz=1", "https://release-assets.githubusercontent.com/x", "HTTPS://GITHUB.COM/a", "https://github.com:443/a"))
            assertTrue(SelfUpdate.hostAllowed(u), u)
        for (u in listOf("http://github.com/a", "https://raw.githubusercontent.com/a", "https://githubusercontent.com/a", "https://evil.github.com.example/a", "https://github.com.evil.example/a", "https://user@github.com/a", "https://127.0.0.1/a", "https://github.com:444/a", "ftp://github.com/a", "//github.com/a", "github.com/a", "https:///a", ""))
            assertFalse(SelfUpdate.hostAllowed(u), u)
    }

    @Test fun shaFileFormats() {
        assertEquals(sha, SelfUpdate.parseSha("$sha  urtv-player.apk\n")); assertEquals(sha, SelfUpdate.parseSha(sha.uppercase()))
        assertNull(SelfUpdate.parseSha("")); assertNull(SelfUpdate.parseSha("zz".repeat(32))); assertNull(SelfUpdate.parseSha("a".repeat(63))); assertNull(SelfUpdate.parseSha("<html>$sha"))
    }

    @Test fun swappedFileWrongPackageDowngradeAndWrongSignerAreRefused() {
        fun c(actual: String = sha, pub: String? = sha, pkg: String? = "com.urtv.player", code: Long = 70, signers: List<String> = listOf(cert.uppercase()), pin: String = cert) =
            SelfUpdate.check(actual, pub, pkg, "com.urtv.player", code, 68, signers, pin)
        assertEquals(Check.Ok, c())
        assertEquals(Check.BadHash, c(actual = "b".repeat(64)), "swapped file")
        assertEquals(Check.BadHash, c(pub = null))
        assertEquals(Check.WrongPackage, c(pkg = "com.evil.app")); assertEquals(Check.WrongPackage, c(pkg = null))
        assertEquals(Check.NotNewer, c(code = 68)); assertEquals(Check.NotNewer, c(code = 3), "downgrade")
        assertEquals(Check.WrongSigner, c(signers = listOf("00".repeat(32))))
        assertEquals(Check.MultipleSigners, c(signers = listOf(cert, cert)))
        assertEquals(Check.NoSigner, c(signers = emptyList()))
        assertEquals(Check.WrongSigner, c(pin = ""), "a build without a pinned certificate never accepts an update")
    }
}
