package com.urtv.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The attack surface of the manifest is pinned: which permissions and which components are exported. */
class ManifestSurfaceTest {
    private val xml: String by lazy {
        listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
            .replace(Regex("(?s)<!--.*?-->"), "")     // comments may mention anything
    }

    @Test fun permissionsAreExactlyTheKnownList() {
        val perms = Regex("""<uses-permission android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "sa.urtv.permission.READ_LANG", "android.permission.REQUEST_INSTALL_PACKAGES"), perms)
    }

    @Test fun onlyTheLauncherAndFindMatchAreExportedAndNothingElseListens() {
        val acts = Regex("""<activity\b[^>]*>""").findAll(xml).map { it.value }.toList()
        val exported = acts.filter { it.contains("""android:exported="true"""") }.map { Regex("""android:name="([^"]+)"""").find(it)!!.groupValues[1] }
        assertEquals(listOf(".MainActivity", ".FindMatchActivity"), exported)
        assertEquals(0, Regex("""<service""").findAll(xml).count(), "no services")
        assertEquals(0, Regex("""<provider[^>]*android:name""").findAll(xml).count(), "no content providers of our own (the provider lines inside <queries> only name UrTV's)")
        val receivers = Regex("""<receiver\b[^>]*>""").findAll(xml).map { it.value }.toList()
        assertEquals(1, receivers.size); assertTrue(receivers[0].contains("""tools:node="remove""""), "the only receiver mentioned is the removed profile-installer one")
    }
}
