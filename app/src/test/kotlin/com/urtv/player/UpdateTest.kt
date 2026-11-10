package com.urtv.player

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.urtv.player.data.Update
import com.urtv.player.data.Update.Kind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stand-in for UrTV's read-only provider. */
class FakeUpdateProvider : ContentProvider() {
    companion object { @Volatile var row: Array<Any?>? = null; @Volatile var boom = false; @Volatile var nullCursor = false }
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        if (boom) throw IllegalStateException("provider crashed"); if (nullCursor) return null
        return MatrixCursor(arrayOf("version_code", "version_name", "notes")).also { c -> row?.let { c.addRow(it) } }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun withProvider(block: () -> Unit) {
        Robolectric.setupContentProvider(FakeUpdateProvider::class.java, Update.AUTHORITY)
        FakeUpdateProvider.row = null; FakeUpdateProvider.boom = false; FakeUpdateProvider.nullCursor = false
        block()
    }

    @Test fun newerSignedPlayerIsOffered() = withProvider {
        FakeUpdateProvider.row = arrayOf(70, "0.9.4", "إصلاحات التشغيل")
        val s = Update.read(ctx, currentCode = 68, trust = { true })
        assertEquals(Kind.Available, s.kind); assertEquals(70, s.offer?.code); assertEquals("0.9.4", s.offer?.name); assertEquals("إصلاحات التشغيل", s.offer?.notes)
    }

    @Test fun sameOrOlderOrNoRowMeansUpToDate() = withProvider {
        FakeUpdateProvider.row = arrayOf(68, "0.9.2", ""); assertEquals(Kind.UpToDate, Update.read(ctx, 68, trust = { true }).kind)
        FakeUpdateProvider.row = arrayOf(60, "0.9.0", ""); assertEquals(Kind.UpToDate, Update.read(ctx, 68, trust = { true }).kind)
        FakeUpdateProvider.row = null; assertEquals(Kind.UpToDate, Update.read(ctx, 68, trust = { true }).kind)
    }

    @Test fun untrustedOrBrokenOrMissingProviderSaysUpdatesComeFromTheTv() = withProvider {
        FakeUpdateProvider.row = arrayOf(99, "9.9.9", "x")
        assertEquals(Kind.NoUrtv, Update.read(ctx, 68, trust = { false }).kind, "not UrTV's provider / certificate")
        FakeUpdateProvider.boom = true; assertEquals(Kind.NoUrtv, Update.read(ctx, 68, trust = { true }).kind)
        FakeUpdateProvider.boom = false; FakeUpdateProvider.nullCursor = true; assertEquals(Kind.NoUrtv, Update.read(ctx, 68, trust = { true }).kind)
        assertEquals(Kind.NoUrtv, Update.read(ctx, 68).kind, "the real check refuses this test app (wrong package and certificate)")
    }

    @Test fun hostileRowsAreRefusedOrCleaned() {
        assertNull(Update.offerFrom(70, "0.9.4 http://evil.example", "n", 68)); assertNull(Update.offerFrom(70, "", "n", 68)); assertNull(Update.offerFrom(null, "0.9.4", "n", 68))
        assertNull(Update.offerFrom(Int.MIN_VALUE, "0.9.4", "n", 68))
        val o = Update.offerFrom(70, "0.9.4", "line\u0000one\n" + "x".repeat(500), 68)!!
        assertTrue(o.notes.length <= 120 && o.notes.none { it.isISOControl() })
    }

    @Test fun askedAtMostOncePerDayAndClockGoingBackAsksAgain() {
        val day = 24L * 3_600_000L
        assertTrue(Update.due(0, 1000)); assertFalse(Update.due(1000, 1000 + day - 1)); assertTrue(Update.due(1000, 1000 + day)); assertTrue(Update.due(5000, 100))
    }

    @Test fun lastAnswerIsRememberedAndForgottenOnceInstalled() {
        val sp = ctx.getSharedPreferences("upd-test", 0)
        Update.save(sp, Update.State(Kind.Available, Update.Offer(70, "0.9.4", "n")), 123)
        assertEquals(Kind.Available, Update.load(sp, currentCode = 68).kind)
        assertEquals(Kind.UpToDate, Update.load(sp, currentCode = 70).kind, "after installing it, nothing is offered any more")
        Update.save(sp, Update.State(Kind.NoUrtv), 124); assertEquals(Kind.NoUrtv, Update.load(sp, 68).kind)
    }
}
