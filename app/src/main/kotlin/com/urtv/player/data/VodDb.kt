package com.urtv.player.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.urtv.player.core.SearchFold
import com.urtv.player.core.Tmdb

/** One film (kind 0) or series (kind 1) as the screens need it. Stream URLs are NOT stored here: they are built from the account and [ext] (so no credentials sit in this file). */
data class VodRow(
    val kind: Int, val id: String, val name: String, val cat: String, val poster: String?, val rating: String?, val year: String?,
    val added: Long, val ext: String = "", val plot: String = "", val seen: Long = 0,
) {
    /** First seen after the very first load of that list, within [FRESH_MS]: shown with a «جديد» badge. */
    fun fresh(now: Long = System.currentTimeMillis()) = seen > 0 && now - seen < FRESH_MS
    companion object { const val FRESH_MS = 3L * 24 * 3_600_000; const val MOVIE = 0; const val SERIES = 1 }
}

/** What a list of posters asks of the index: a category (or all), a search, a set of ids (favourites) or the newest. */
data class VodQuery(val pid: String, val kind: Int? = null, val cat: String? = null, val text: String = "", val ids: Set<String>? = null, val newest: Boolean = false, val limit: Int = Int.MAX_VALUE)

/**
 * The movies and series of every playlist on disk (SQLite, WAL), read in pages: nothing but the page on screen is ever in memory, so 150 000 films
 * cost a few MB instead of ~100. Writes go in small transactions, so reading never waits for a refresh. Every call blocks: use it off the main thread
 * (a single-row [get] by key is fine anywhere).
 */
class VodDb(ctx: Context, name: String = "vod.db") : SQLiteOpenHelper(ctx, name, null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE vod(pid TEXT NOT NULL, kind INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, norm TEXT NOT NULL, tn TEXT NOT NULL, cat TEXT NOT NULL,
            poster TEXT, rating TEXT, year TEXT, added INTEGER NOT NULL, ext TEXT NOT NULL, plot TEXT NOT NULL, ord INTEGER NOT NULL, gen INTEGER NOT NULL, seen INTEGER NOT NULL,
            PRIMARY KEY(pid, kind, id))""")
        db.execSQL("CREATE INDEX vod_cat ON vod(pid, kind, cat, ord)")
        db.execSQL("CREATE INDEX vod_ord ON vod(pid, kind, ord)")
        db.execSQL("CREATE INDEX vod_added ON vod(pid, added)")
        db.execSQL("CREATE INDEX vod_tn ON vod(pid, tn)")
        db.execSQL("CREATE TABLE meta(pid TEXT NOT NULL, k TEXT NOT NULL, v TEXT NOT NULL, PRIMARY KEY(pid, k))")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) { db.execSQL("DROP TABLE IF EXISTS vod"); db.execSQL("DROP TABLE IF EXISTS meta"); onCreate(db) }

    private val COLS = "kind,id,name,cat,poster,rating,year,added,ext,plot,seen"
    private fun row(c: Cursor) = VodRow(c.getInt(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7), c.getString(8), c.getString(9), c.getLong(10))
    /** A damaged or full disk answers «nothing» (and is noted in the log) instead of ending the app. */
    private fun <T> q(sql: String, args: List<String>, empty: T, f: (Cursor) -> T): T = try { readableDatabase.rawQuery(sql, args.toTypedArray()).use(f) } catch (e: android.database.sqlite.SQLiteException) { Diag.record(e); empty }

    fun count(pid: String, kind: Int? = null): Int = q("SELECT COUNT(*) FROM vod WHERE pid=?" + (kind?.let { " AND kind=$it" } ?: ""), listOf(pid), 0) { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Categories of one kind in the provider's order with how many items each has. */
    fun cats(pid: String, kind: Int): List<Pair<String, Int>> = q("SELECT cat, COUNT(*), MIN(ord) m FROM vod WHERE pid=? AND kind=? GROUP BY cat ORDER BY m", listOf(pid, kind.toString()), emptyList()) { c ->
        val out = ArrayList<Pair<String, Int>>(); while (c.moveToNext()) out += c.getString(0) to c.getInt(1); out
    }

    private fun where(v: VodQuery, args: MutableList<String>): String {
        val sb = StringBuilder("pid=?"); args += v.pid
        v.kind?.let { sb.append(" AND kind=").append(it) }
        v.cat?.let { sb.append(" AND cat=?"); args += it }
        val views = SearchFold.queryViews(v.text)
        if (views.isNotEmpty()) { sb.append(" AND ("); views.forEachIndexed { i, w -> if (i > 0) sb.append(" OR "); sb.append("instr(norm,?)>0"); args += w }; sb.append(')') }
        v.ids?.let { ids -> if (ids.isEmpty()) sb.append(" AND 0") else { val l = ids.take(500); sb.append(" AND id IN (").append(l.joinToString(",") { "?" }).append(')'); args += l } }
        return sb.toString()
    }

    /** How many rows [v] matches (the newest are capped at [VodQuery.limit]). */
    fun size(v: VodQuery): Int { val a = ArrayList<String>(); val n = q("SELECT COUNT(*) FROM vod WHERE " + where(v, a), a, 0) { if (it.moveToFirst()) it.getInt(0) else 0 }; return minOf(n, v.limit) }

    /** [limit] rows from [offset] in the provider's order (or newest first). */
    fun page(v: VodQuery, offset: Int, limit: Int): List<VodRow> {
        val a = ArrayList<String>()
        val w = where(v, a)
        val take = minOf(limit, v.limit - offset).coerceAtLeast(0)
        if (take == 0) return emptyList()
        return q("SELECT $COLS FROM vod WHERE $w ORDER BY " + (if (v.newest) "added DESC, ord" else "ord") + " LIMIT $take OFFSET $offset", a, emptyList()) { c -> val out = ArrayList<VodRow>(take); while (c.moveToNext()) out += row(c); out }
    }

    fun get(pid: String, kind: Int, id: String): VodRow? = q("SELECT $COLS FROM vod WHERE pid=? AND kind=? AND id=?", listOf(pid, kind.toString(), id), null) { if (it.moveToFirst()) row(it) else null }

    /** The newest items of both kinds, for the home row. */
    fun newest(pid: String, n: Int): List<VodRow> = q("SELECT $COLS FROM vod WHERE pid=? ORDER BY added DESC LIMIT $n", listOf(pid), emptyList()) { c -> val out = ArrayList<VodRow>(); while (c.moveToNext()) out += row(c); out }
    fun first(pid: String, kind: Int, n: Int): List<VodRow> = page(VodQuery(pid, kind), 0, n)

    /** Items whose normalised title is one of [titles] (TMDB «trending» matched against what the provider really has), best-known first. */
    fun byTitles(pid: String, titles: List<String>): List<VodRow> {
        val out = ArrayList<VodRow>()
        for (chunk in titles.distinct().chunked(400)) q("SELECT $COLS FROM vod WHERE pid=? AND tn IN (" + chunk.joinToString(",") { "?" } + ")", listOf(pid) + chunk, emptyList<VodRow>()) { c -> while (c.moveToNext()) out += row(c) }
        return out
    }

    fun fresh(pid: String, kind: Int? = null): Int = q("SELECT COUNT(*) FROM vod WHERE pid=?" + (kind?.let { " AND kind=$it" } ?: "") + " AND seen>?", listOf(pid, (System.currentTimeMillis() - VodRow.FRESH_MS).toString()), 0) { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun meta(pid: String, k: String): String? = q("SELECT v FROM meta WHERE pid=? AND k=?", listOf(pid, k), null) { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(pid: String, k: String, v: String) { writableDatabase.execSQL("INSERT OR REPLACE INTO meta(pid,k,v) VALUES(?,?,?)", arrayOf(pid, k, v)) }

    fun forget(pid: String) { val d = writableDatabase; d.delete("vod", "pid=?", arrayOf(pid)); d.delete("meta", "pid=?", arrayOf(pid)) }

    /** What one scope (a category, or a whole kind) of a refresh changed. */
    data class Diff(val seen: Int, val added: Int, val removed: Int)

    /**
     * Writes [rows] (the provider's answer for one scope: a category when [cat] is set, else the whole kind) as a diff against what is stored: new items are
     * inserted (marked «seen now» unless the list had nothing before), known ones are updated in place, and the scope's items that did not come back are
     * deleted afterwards. Rows go in batches of 1 000 per transaction. Position numbers start at [ordBase]; [initial]: the list had nothing before, so nothing is marked new. The sweep is skipped when the answer was empty
     * or [complete] says it was cut short (a half answer must never wipe a category).
     */
    fun apply(pid: String, kind: Int, cat: String?, rows: Sequence<VodRow>, ordBase: Long, initial: Boolean, now: Long = System.currentTimeMillis(), complete: () -> Boolean = { true }, onBatch: (Int) -> Unit = {}): Diff {
        val d = writableDatabase
        val gen = now
        val had = !initial          // the very first load of a list marks nothing as new
        val upd = d.compileStatement("UPDATE vod SET name=?,norm=?,tn=?,cat=?,poster=?,rating=?,year=?,added=?,ext=?,plot=?,ord=?,gen=? WHERE pid=? AND kind=? AND id=?")
        val ins = d.compileStatement("INSERT OR REPLACE INTO vod(pid,kind,id,name,norm,tn,cat,poster,rating,year,added,ext,plot,ord,gen,seen) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        var n = 0; var added = 0; var i = 0L
        try {
            val it = rows.iterator()
            while (it.hasNext()) {
                d.beginTransaction()
                try {
                    var k = 0
                    while (k < 1000 && it.hasNext()) {
                        val r = it.next(); k++; n++
                        val norm = SearchFold.fold(r.name); val tn = Tmdb.normalize(r.name)
                        val ord = ordBase + i++
                        var changed = 0
                        if (had) {
                            upd.clearBindings()
                            upd.bindString(1, r.name); upd.bindString(2, norm); upd.bindString(3, tn); upd.bindString(4, r.cat); r.poster?.let { upd.bindString(5, it) } ?: upd.bindNull(5)
                            r.rating?.let { upd.bindString(6, it) } ?: upd.bindNull(6); r.year?.let { upd.bindString(7, it) } ?: upd.bindNull(7)
                            upd.bindLong(8, r.added); upd.bindString(9, r.ext); upd.bindString(10, r.plot); upd.bindLong(11, ord); upd.bindLong(12, gen)
                            upd.bindString(13, pid); upd.bindLong(14, r.kind.toLong()); upd.bindString(15, r.id)
                            changed = upd.executeUpdateDelete()
                        }
                        if (changed == 0) {
                            added++
                            ins.clearBindings()
                            ins.bindString(1, pid); ins.bindLong(2, r.kind.toLong()); ins.bindString(3, r.id); ins.bindString(4, r.name); ins.bindString(5, norm); ins.bindString(6, tn); ins.bindString(7, r.cat)
                            r.poster?.let { ins.bindString(8, it) } ?: ins.bindNull(8); r.rating?.let { ins.bindString(9, it) } ?: ins.bindNull(9); r.year?.let { ins.bindString(10, it) } ?: ins.bindNull(10)
                            ins.bindLong(11, r.added); ins.bindString(12, r.ext); ins.bindString(13, r.plot); ins.bindLong(14, ord); ins.bindLong(15, gen); ins.bindLong(16, if (had) now else 0)
                            ins.executeInsert()
                        }
                    }
                    d.setTransactionSuccessful()
                } finally { d.endTransaction() }
                onBatch(n)
            }
        } finally { upd.close(); ins.close() }
        var removed = 0
        if (n > 0 && complete()) {
            val args = if (cat != null) arrayOf(pid, kind.toString(), cat, gen.toString()) else arrayOf(pid, kind.toString(), gen.toString())
            removed = d.delete("vod", "pid=? AND kind=?" + (if (cat != null) " AND cat=?" else "") + " AND gen<>?", args)
        }
        return Diff(n, added, removed)
    }
}
