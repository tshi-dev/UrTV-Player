package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.data.Backup
import com.urtv.player.data.PlaylistDef
import com.urtv.player.data.Store
import com.urtv.player.phone.PhoneMode
import com.urtv.player.phone.PhoneServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupCryptoTest {
    private val iter = 10_000   // fast for tests; production uses DEFAULT_ITERATIONS

    @Test fun roundTripAndNothingPlainInTheFile() {
        val secret = "http://example.com/get.php?username=user&password=s3cr3t-token"
        val blob = Backup.encrypt(secret.toByteArray(), "correct horse".toCharArray(), iter)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("s3cr3t"), "credentials must not appear in the file")
        assertEquals(secret, Backup.decrypt(blob, "correct horse".toCharArray())?.toString(Charsets.UTF_8))
    }

    @Test fun wrongPassphraseTamperAndGarbageAreRejected() {
        val blob = Backup.encrypt("data".toByteArray(), "passphrase1".toCharArray(), iter)
        assertNull(Backup.decrypt(blob, "passphrase2".toCharArray()))
        for (i in listOf(0, 5, 10, 30, 40, blob.size - 1)) assertNull(Backup.decrypt(blob.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, "passphrase1".toCharArray()), "byte $i")
        assertNull(Backup.decrypt(ByteArray(10), "x".toCharArray()))
        assertNull(Backup.decrypt("not a backup, just some text that is long enough to pass the size check".toByteArray(), "x".toCharArray()))
    }

    @Test fun eachBackupUsesFreshSaltAndIv() {
        val a = Backup.encrypt("same".toByteArray(), "passphrase1".toCharArray(), iter); val b = Backup.encrypt("same".toByteArray(), "passphrase1".toCharArray(), iter)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test fun absurdWorkFactorFromAHostileFileIsRefused() {
        val blob = Backup.encrypt("x".toByteArray(), "passphrase1".toCharArray(), iter)
        blob[4] = 0x7f   // iterations field -> ~2 billion
        assertNull(Backup.decrypt(blob, "passphrase1".toCharArray()))
    }
}

class BackupPhoneTest {
    private val lo = InetAddress.getByName("127.0.0.1")
    private fun raw(port: Int, req: ByteArray): String = Socket(lo, port).use { s ->
        s.soTimeout = 3000; s.getOutputStream().write(req); s.getOutputStream().flush()
        runCatching { s.getInputStream().readBytes().toString(Charsets.ISO_8859_1) }.getOrDefault("")
    }
    private fun get(port: Int, path: String) = raw(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray())
    private fun upload(port: Int, code: String, file: ByteArray, declared: Int? = null): String {
        val b = "----x1y2"; val body = ByteArrayOutputStream()
        body.write("--$b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"b.urb\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray()); body.write(file); body.write("\r\n--$b--\r\n".toByteArray())
        val bytes = body.toByteArray()
        return raw(port, ("POST /restore?c=$code HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Type: multipart/form-data; boundary=$b\r\nContent-Length: ${declared ?: bytes.size}\r\nConnection: close\r\n\r\n").toByteArray() + bytes)
    }

    @Test fun backupIsDownloadedOnceWithTheRightCodeAndThenTheDoorCloses() {
        val blob = ByteArray(300) { it.toByte() }; val closed = CountDownLatch(1)
        val s = PhoneServer(lo, {}, { closed.countDown() }, mode = PhoneMode.Backup, backupBlob = blob); assertTrue(s.start())
        assertTrue(get(s.port, "/backup?c=000000".takeIf { s.code != "000000" } ?: "/backup?c=111111").startsWith("HTTP/1.1 403"))
        val r = get(s.port, "/backup?c=${s.code}")
        assertTrue(r.startsWith("HTTP/1.1 200") && r.contains("Content-Disposition: attachment"), r.take(200))
        assertContentEquals(blob, r.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        assertTrue(closed.await(5, TimeUnit.SECONDS))
    }

    @Test fun restoreReceivesTheUploadedFileOnlyWithTheCodeAndWithinTheSizeCap() {
        var got: ByteArray? = null; val closed = CountDownLatch(1)
        val s = PhoneServer(lo, {}, { closed.countDown() }, mode = PhoneMode.Restore, onBlob = { got = it }); assertTrue(s.start())
        assertTrue(upload(s.port, "123456".takeIf { s.code != "123456" } ?: "654321", ByteArray(10)).startsWith("HTTP/1.1 403"))
        assertTrue(upload(s.port, s.code, ByteArray(10), declared = 5_000_000).startsWith("HTTP/1.1 413"), "oversize upload must be refused")
        val payload = ByteArray(2000) { (it * 7).toByte() }
        assertTrue(upload(s.port, s.code, payload).startsWith("HTTP/1.1 200"))
        assertTrue(closed.await(5, TimeUnit.SECONDS)); assertContentEquals(payload, got)
    }

    @Test fun addEndpointIsNotReachableInBackupMode() {
        val s = PhoneServer(lo, {}, {}, mode = PhoneMode.Backup, backupBlob = ByteArray(1)); assertTrue(s.start())
        try { assertTrue(raw(s.port, "POST /add HTTP/1.1\r\nHost: 127.0.0.1:${s.port}\r\nContent-Length: 0\r\n\r\n".toByteArray()).startsWith("HTTP/1.1 404")) } finally { s.close() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class StoreBackupTest {
    @Test fun exportImportRoundTripKeepsPlaylistsAndSettingsButNotThePin() {
        val app = ApplicationProvider.getApplicationContext<UrtvApp>(); val a = app.store
        a.addPlaylist(PlaylistDef("p1", "Example", PlaylistDef.KIND_XTREAM, server = "http://example.com", user = "u", pass = "secret"))
        a.setLang("en"); a.setStartScreen("live"); a.setHomeRows(listOf("favs", "continue")); a.toggleFav("p1|abc"); a.createList("p1", "Mine"); a.toggleInList("p1", "Mine", "p1|abc")
        a.setPin("1234")
        val json = a.exportJson()
        assertFalse(json.contains("1234"), "the PIN must not be exported")
        a.removePlaylist("p1"); a.setLang("ar"); a.setStartScreen("home"); a.setHomeRows(listOf("recent"))
        assertTrue(a.importJson(json))
        assertEquals("secret", a.playlists.value.single().pass); assertEquals("en", a.lang.value); assertEquals("live", a.startScreen.value)
        assertEquals(listOf("favs", "continue"), a.homeRows.value); assertEquals(setOf("p1|abc"), a.customLists.value["p1|Mine"])
    }

    @Test fun importRejectsNonBackupsAndClampsHostileValues() {
        val a = ApplicationProvider.getApplicationContext<UrtvApp>().store
        assertFalse(a.importJson("not json")); assertFalse(a.importJson("""{"v":2}""")); assertFalse(a.importJson("[]"))
        assertTrue(a.importJson("""{"v":1,"refreshH":99999,"start":"evil","lang":"xx","textScale":9,"epgOff":{"p":100000},"playlists":[{"id":"x","name":"n","kind":"file","url":"file:///etc/passwd"}]}"""))
        assertEquals(24, a.refreshHours.value.takeIf { it == 24 } ?: a.refreshHours.value.also { assertTrue(it <= 168) })
        assertNotEquals("evil", a.startScreen.value); assertTrue(a.playlists.value.none { it.kind == "file" })
    }
}
