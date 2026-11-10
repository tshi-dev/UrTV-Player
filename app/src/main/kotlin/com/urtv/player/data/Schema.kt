package com.urtv.player.data

import android.content.SharedPreferences

/**
 * Version of the data the app keeps (preferences + encrypted JSON files). Every released version so far (0.9.0 to 0.9.3) wrote the
 * same formats and later versions only ADD keys/fields (readers ignore unknown ones), so opening older data needs no conversion:
 * [check] just stamps the schema. Data stamped by a NEWER schema is never rewritten or deleted: the app shows a short notice and
 * stops there (Android itself already refuses to install an older APK over a newer one).
 */
object Schema {
    const val CURRENT = 1
    enum class Status { Current, Migrated, TooNew }

    fun check(sp: SharedPreferences): Status {
        val v = sp.getInt("schema", 0)
        return when {
            v > CURRENT -> Status.TooNew
            v == CURRENT -> Status.Current
            else -> { sp.edit().putInt("schema", CURRENT).apply(); Status.Migrated }   // 0 = written by 0.9.0..0.9.3: same formats
        }
    }
}
