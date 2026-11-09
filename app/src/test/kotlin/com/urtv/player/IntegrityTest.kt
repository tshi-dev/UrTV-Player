package com.urtv.player

import com.urtv.player.data.Integrity
import com.urtv.player.data.Integrity.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class IntegrityTest {
    private val pin = "AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89:AB:CD:EF:01:23:45:67:89"
    private val same = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

    @Test fun officialOnlyWhenEveryCurrentSignerMatchesThePin() {
        assertEquals(Status.Official, Integrity.evaluate(pin, listOf(same), debugBuild = false), "colons and case do not matter")
        assertEquals(Status.Unofficial, Integrity.evaluate(pin, listOf("00" + same.drop(2)), false), "another certificate")
        assertEquals(Status.Unofficial, Integrity.evaluate(pin, listOf(same, "11" + same.drop(2)), false), "an extra unknown signer")
        assertEquals(Status.Unofficial, Integrity.evaluate(pin, emptyList(), false), "no readable certificate")
    }

    @Test fun buildWithoutAPinIsNeverOfficial() {
        assertEquals(Status.Unofficial, Integrity.evaluate("", listOf(same), false)); assertEquals(Status.Unofficial, Integrity.evaluate("   ", listOf(same), false))
    }

    @Test fun debugBuildsAreLabelledDebugRegardlessOfTheCertificate() {
        assertEquals(Status.Debug, Integrity.evaluate(pin, listOf(same), true)); assertEquals(Status.Debug, Integrity.evaluate("", emptyList(), true))
    }

    @Test fun normalizeStripsSeparators() = assertEquals("abcd01", Integrity.normalize("AB:cd 01"))
}
