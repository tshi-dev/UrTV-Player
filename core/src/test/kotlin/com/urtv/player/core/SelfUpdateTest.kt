package com.urtv.player.core

import com.urtv.player.core.SelfUpdate.Check
import kotlin.test.*

class SelfUpdateTest {
    private val cert = "13ade7d009a07f5ba5bab0b7734bd449df10dd47b53868dfa28e44cd8069dfea"
    private val sha = "a".repeat(64)
    @Test fun versionAssetNewerThanInstalledIsOfferedAndEverythingElseIsNot() {
        val r = SelfUpdate.parseVersion("70 0.9.4\n", installedCode = 68, installedName = "0.9.3")
        assertEquals("0.9.4", r?.version); assertEquals(70L, r?.code); assertEquals(SelfUpdate.APK_URL, r?.apkUrl); assertEquals(SelfUpdate.SHA_URL, r?.shaUrl)
        assertEquals("0.9.11", SelfUpdate.parseVersion("90 0.9.11", 80, "0.9.10")?.version, "numeric, not text, comparison")
        assertNull(SelfUpdate.parseVersion("68 0.9.3", 68, "0.9.3"), "same version")
        assertNull(SelfUpdate.parseVersion("60 0.9.0", 68, "0.9.3"), "downgrade")
        assertNull(SelfUpdate.parseVersion("70 0.9.2", 68, "0.9.3"), "code up but name not newer: inconsistent, refused")
        assertNull(SelfUpdate.parseVersion("99 0.9.9", 99, "0.9.3"), "name newer but code not higher: refused")
    }

    @Test fun hostileVersionAssetsAreIgnoredNeverThrow() {
        for (b in listOf("", "null", "{}", "<html>404</html>", "70", "70 ", "70 0.9.4 extra", "70 0.9.4\n71 0.9.5", "70  0.9.4", "-70 0.9.4", "99999999999 0.9.4", "70 0.9.4; rm -rf /", "70 http://evil.example", "70 0.9.4\u0000", "7\u0660 0.9.4", "70 9".repeat(200), "\u0000".repeat(10)))
            assertNull(SelfUpdate.parseVersion(b, 68, "0.9.3"), b.take(30))
    }

    @Test fun stableLinksAreOnPinnedHostsAndTheCdnTheyRedirectToToo() {
        for (u in listOf(SelfUpdate.VERSION_URL, SelfUpdate.APK_URL, SelfUpdate.SHA_URL, "https://objects.githubusercontent.com/github-production-release-asset/1?X=2", "https://release-assets.githubusercontent.com/github-production-release-asset/2")) assertTrue(SelfUpdate.hostAllowed(u), u)
        assertTrue(SelfUpdate.LATEST.endsWith("/releases/latest/download/"))
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
        assertEquals(Check.Ok, SelfUpdate.check(sha, sha, "com.urtv.player", "com.urtv.player", 70, 68, listOf(cert), cert, expectedCode = 70))
        assertEquals(Check.WrongVersion, SelfUpdate.check(sha, sha, "com.urtv.player", "com.urtv.player", 71, 68, listOf(cert), cert, expectedCode = 70), "latest moved or file swapped")
        assertEquals(Check.WrongSigner, c(signers = listOf("00".repeat(32))))
        assertEquals(Check.MultipleSigners, c(signers = listOf(cert, cert)))
        assertEquals(Check.NoSigner, c(signers = emptyList()))
        assertEquals(Check.WrongSigner, c(pin = ""), "a build without a pinned certificate never accepts an update")
    }
}
