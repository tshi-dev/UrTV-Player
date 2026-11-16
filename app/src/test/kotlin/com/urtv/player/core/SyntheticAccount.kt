package com.urtv.player.core

/** A made-up big Xtream account (no real provider data): JSON answers the way get_vod_streams / get_live_streams / get_series do. */
object SyntheticAccount {
    const val VOD = 150_000; const val LIVE = 55_000; const val SERIES = 20_000; const val CATS = 300

    fun cats(n: Int = CATS): String = (1..n).joinToString(",", "[", "]") { """{"category_id":"$it","category_name":"فئة $it | Category $it","parent_id":0}""" }
    fun vod(n: Int = VOD, cats: Int = CATS, firstId: Int = 1, addedBase: Long = 1_700_000_000L): String = (firstId until firstId + n).joinToString(",", "[", "]") {
        """{"num":$it,"name":"فيلم رقم $it Movie Number $it (${1980 + it % 45})","stream_type":"movie","stream_id":$it,"stream_icon":"http://img.example/p/$it.jpg","rating":"${it % 10}.${it % 7}","added":"${addedBase + it}","category_id":"${it % cats + 1}","container_extension":"mkv"}"""
    }
    fun live(n: Int = LIVE, cats: Int = CATS): String = (1..n).joinToString(",", "[", "]") {
        """{"num":$it,"name":"قناة $it HD / Channel $it","stream_type":"live","stream_id":$it,"stream_icon":"http://img.example/l/$it.png","epg_channel_id":"ch$it.xx","added":"1700000000","category_id":"${it % cats + 1}","tv_archive":0}"""
    }
    fun series(n: Int = SERIES, cats: Int = 60): String = (1..n).joinToString(",", "[", "]") {
        """{"num":$it,"name":"مسلسل $it Series $it","series_id":$it,"cover":"http://img.example/s/$it.jpg","plot":"قصة المسلسل رقم $it ${"نص ".repeat(20)}","rating":"${it % 10}","releaseDate":"${2000 + it % 24}-01-01","last_modified":"${1_700_000_000L + it}","category_id":"${it % cats + 1}"}"""
    }
}
