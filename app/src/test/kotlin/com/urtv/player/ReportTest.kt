package com.urtv.player

import com.urtv.player.data.Diag
import com.urtv.player.data.PlaylistDef
import com.urtv.player.data.Report
import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    private val hostile = listOf(
        PlaylistDef("1", "http://evil.example/get.php?username=bob&password=s3cret", "m3u", "http://10.0.0.5:8080/pl.m3u?token=abc123", "", "", "", "http://epg.evil.example/x.xml"),
        PlaylistDef("2", "My IPTV 192.168.1.20 tok_9f8e7d", "xtream", "", "http://provider.example:8000", "alice@mail.example", "hunter2", ""),
        PlaylistDef("3", "اسم قائمة <script>alert(1)</script> https://x.y/z", "m3u", "https://secret.host/list", "", "", "", ""),
    )
    private fun info(errors: List<String> = listOf("IOException", "SocketTimeoutException")) = Report.Info(
        "0.9.3", "official", "14", 34, "TCL", "normal", "ar", "Default", "Blue", hostile, 1,
        "avc1.640028", "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", errors,
    )

    private val forbidden = listOf("evil", "example", "10.0.0", "192.168", "bob", "s3cret", "abc123", "alice", "hunter2", "provider", "secret.host", "token", "tok_9f8e7d", "script", "http", "://", "@", "My IPTV", "اسم")

    @Test fun summaryNeverContainsPlaylistNamesLinksHostsAddressesLoginsOrTokens() {
        for (kind in Report.Kind.entries) {
            val t = Report.build(kind, "playback", info())
            for (w in forbidden) assertFalse(t.contains(w, ignoreCase = true), "'$w' leaked into: $t")
            assertTrue(t.contains("playlists 3 (m3u 2, xtream 1)"), t)
        }
    }

    @Test fun everyLineStartsWithAnAllowedPrefixAndFirstLineIsAccepted() {
        val p = Report.build(Report.Kind.Problem, "guide", info()).trim().lines()
        assertTrue(p.first().startsWith("UrTV problem report, "), p.first()); assertTrue(p.first().endsWith("topic: guide"))
        val r = Report.build(Report.Kind.Request, "other", info()).trim().lines()
        assertTrue(r.first().startsWith("UrTV setup report, ")); assertTrue(r.first().endsWith("feature request"))
        for (l in p + r) assertTrue(Regex("UrTV (problem|setup) report, |Versions:|Device:|Extras:").find(l)?.range?.first == 0, l)
        assertEquals(2, r.count { it.startsWith("Extras:") } + 0, "a request has no error lines"); 
    }

    @Test fun hostileErrorNamesAndTopicsAreNeutralised() {
        val t = Report.build(Report.Kind.Problem, "http://evil.example", info(listOf("java.io.IOException: http://a.b/c?token=1", "Exception@host", "X".repeat(500), "")))
        assertTrue(t.contains("topic: other"), t)
        for (w in listOf("evil", "http", "token", "@host", "://")) assertFalse(t.contains(w, true), t)
        assertTrue(t.lines().all { it.length <= 400 })
    }

    @Test fun sanitizeDropsForeignLinesAndLinkLikeWords() {
        val s = Report.sanitize("Hello there\nExtras: ok https://x.y/z a@b 10.1.2.3 fine\nDevice: TCL\n")
        assertEquals("Extras: ok fine\nDevice: TCL\n", s)
    }

    @Test fun diagKeepsOnlyClassNamesNotMessages() {
        Diag.clearForTest()
        Diag.record(java.io.IOException("http://secret.example/x?token=1", java.net.SocketTimeoutException("10.0.0.1")))
        Diag.playerError("ERROR_CODE_IO_BAD_HTTP_STATUS", null); Diag.playerError("not a code http://x", null)
        assertEquals(listOf("SocketTimeoutException", "IOException"), Diag.recent(), "newest first")
        assertEquals("", Diag.lastPlayerError)
        repeat(30) { Diag.record(IllegalStateException("m$it")) }
        assertTrue(Diag.recent(5).size <= 5)
    }

    @Test fun packedLinkMatchesTheSharePageFormat() {
        val text = Report.build(Report.Kind.Problem, "lists", info())
        val link = Report.link(text)
        assertTrue(link.startsWith("https://tshi-dev.github.io/UrTV/share/#z."))
        val b64 = link.substringAfter("#z."); assertFalse(b64.contains('=') || b64.contains('+') || b64.contains('/'))
        val inf = Inflater(true); inf.setInput(Base64.getUrlDecoder().decode(b64)); val buf = ByteArray(4096); val n = inf.inflate(buf)
        assertEquals(text, String(buf, 0, n, Charsets.UTF_8))
        assertTrue(link.length < 700, "short enough for a clean QR: ${link.length}")
    }
}
