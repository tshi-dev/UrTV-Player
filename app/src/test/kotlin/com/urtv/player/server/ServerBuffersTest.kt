package com.urtv.player.server

import com.urtv.player.player.PlaybackTuning
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerBuffersTest {
    @Test fun largeFilesAreBoundedByBytesAndStartFast() {
        val b = PlaybackTuning.serverBuffers(lowRam = false)
        assertEquals(1_500, b.startMs); assertEquals(96 * 1024 * 1024, b.targetBytes); assertTrue(b.maxMs >= 60_000)
    }
    @Test fun lowRamBoxesGetASmallerBudget() {
        val b = PlaybackTuning.serverBuffers(lowRam = true)
        assertEquals(24 * 1024 * 1024, b.targetBytes); assertTrue(b.maxMs <= 30_000)
    }
}
