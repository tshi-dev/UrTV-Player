package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import com.urtv.player.data.PlaylistDef
import com.urtv.player.data.Schema
import com.urtv.player.data.Secrets
import com.urtv.player.data.Store
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Data written by every released Player (0.9.0 to 0.9.3 share one format; later versions only added keys, see Schema) opens in
 * this one, and data stamped by a newer schema is left exactly as it is. The fixtures are the 0.9.0 formats written by hand
 * (the oldest, so they also cover what was added later by being absent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class MigrationTest {
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()

    private fun writeV090Fixture() {
        app.getSharedPreferences("urtv", 0).edit().clear()
            .putString("lists", "legacy").putString("active", "p1").putStringSet("favs", setOf("p1|aaaa1111", "p1|bbbb2222"))
            .putString("recents", "p1|aaaa1111\np1|bbbb2222").putString("last", "p1|aaaa1111").putString("lang", "en")
            .putInt("refreshH", 12).putString("buffer", "High").putBoolean("resume", false).putBoolean("stats", true)
            .putStringSet("locked", setOf("p1|Adults")).putStringSet("hidden", setOf("p1|News")).putString("start", "live")
            .putString("pinHash", "abc").putString("pinSalt", "def").putInt("textScale", 110)
            .putString("homeRows", "continue,favs").commit()
        Secrets.writeText(File(app.filesDir, "playlists.json"), """[{"id":"p1","name":"Mine","kind":"m3u","url":"http://x.example/a.m3u","epgUrl":"http://x.example/e.xml"},
            {"id":"p2","name":"Xt","kind":"xtream","server":"http://h.example:8000","user":"u","pass":"p"}]""")
        Secrets.writeText(File(app.filesDir, "watching.json"), """[{"key":"p2|m1","pid":"p2","kind":"movie","title":"Film","url":"http://h.example/m1.mp4","posMs":60000,"durMs":7200000,"updated":1700000000000}]""")
    }

    @Test fun dataFromTheOldestReleaseOpensInTheCurrentOne() {
        writeV090Fixture()
        val sp = app.getSharedPreferences("urtv", 0)
        assertEquals(Schema.Status.Migrated, Schema.check(sp), "no schema stamp = written by 0.9.0 to 0.9.3")
        assertEquals(Schema.Status.Current, Schema.check(sp), "stamped once")
        val s = Store(app); s.loadHeavy()
        assertEquals(listOf("p1", "p2"), s.playlists.value.map { it.id }); assertEquals(PlaylistDef.KIND_XTREAM, s.playlists.value[1].kind)
        assertEquals("p1", s.activeId.value); assertEquals(setOf("p1|aaaa1111", "p1|bbbb2222"), s.favs.value)
        assertEquals(2, s.recents.value.size); assertEquals("p1|aaaa1111", s.lastKey())
        assertEquals("en", s.lang.value); assertEquals(12, s.refreshHours.value); assertEquals(com.urtv.player.data.BufferMode.High, s.buffer.value)
        assertEquals(false, s.resumeLast.value); assertEquals(true, s.statsOverlay.value); assertTrue(s.hasPin.value)
        assertEquals(setOf("p1|Adults"), s.lockedGroups.value); assertEquals(setOf("p1|News"), s.hiddenGroups.value); assertEquals("live", s.startScreen.value)
        assertEquals(1, s.watching.value.size); assertEquals(60000L, s.watching.value[0].posMs)
        // settings added by later releases simply start at their defaults
        assertEquals(false, s.autoAlt.value); assertEquals(null, s.matchPick("premier league"))
    }

    @Test fun fieldsAddedByNewerReleasesAreIgnoredNotFatal() {
        writeV090Fixture()
        Secrets.writeText(File(app.filesDir, "playlists.json"), """[{"id":"p9","name":"N","kind":"m3u","url":"http://x.example/a.m3u","someFutureField":{"a":1},"anotherOne":[1,2]}]""")
        val s = Store(app); s.loadHeavy()
        assertEquals(listOf("p9"), s.playlists.value.map { it.id })
    }

    @Test fun dataFromANewerSchemaIsRefusedAndLeftUntouched() {
        writeV090Fixture()
        val sp = app.getSharedPreferences("urtv", 0); sp.edit().putInt("schema", Schema.CURRENT + 5).commit()
        val before = File(app.filesDir, "playlists.json").readBytes(); val prefsBefore = sp.all.toMap()
        assertEquals(Schema.Status.TooNew, Schema.check(sp))
        assertEquals(prefsBefore, sp.all.toMap(), "the check wrote nothing")
        assertTrue(before.contentEquals(File(app.filesDir, "playlists.json").readBytes()))
    }
}
