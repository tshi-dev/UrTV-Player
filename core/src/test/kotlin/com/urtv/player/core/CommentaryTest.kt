package com.urtv.player.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CommentaryTest {
    @Test fun pressAddsAndResetsAndStaysWithinTenSeconds() {
        assertEquals(1500L, Commentary.press(1000, 500))
        assertEquals(-500L, Commentary.press(0, -500))
        assertEquals(0L, Commentary.press(7300, 0), "the middle button resets")
        assertEquals(10_000L, Commentary.press(8000, 5000)); assertEquals(-10_000L, Commentary.press(-8000, -5000))
    }

    @Test fun theStreamThatIsAheadIsTheOneHeldBack() {
        assertEquals(Commentary.Lag(2000, 0), Commentary.lagFor(2000), "sound later: the sound waits")
        assertEquals(Commentary.Lag(0, 1500), Commentary.lagFor(-1500), "sound earlier: the picture waits")
        assertEquals(Commentary.Lag(0, 0), Commentary.lagFor(0))
        assertEquals(Commentary.Lag(10_000, 0), Commentary.lagFor(99_000), "bounded")
    }

    @Test fun movesAreRelativeSoGoingBackAndForthReturnsToLive() {
        assertEquals(Commentary.Moves(1000, 0), Commentary.plan(0, 1000))
        assertEquals(Commentary.Moves(-500, 0), Commentary.plan(1000, 500))
        // crossing zero: the sound comes back to live while the picture starts to wait
        assertEquals(Commentary.Moves(-500, 1000), Commentary.plan(500, -1000))
        assertEquals(Commentary.Moves(0, -1000), Commentary.plan(-1000, 0))
        var d = 0L; var audio = 0L; var video = 0L
        for (s in listOf(5000L, -1000L, -5000L, 500L, 0L, -500L, 1000L)) { val n = Commentary.press(d, s); val m = Commentary.plan(d, n); audio += m.audioBackMs; video += m.videoBackMs; d = n }
        assertEquals(Commentary.lagFor(d), Commentary.Lag(audio, video), "the sum of the moves is the lag of the final delay")
    }

    @Test fun labelsUseWesternDigitsAndARealMinus() {
        assertEquals("+1.5", Commentary.label(1500)); assertEquals("−0.5", Commentary.label(-500)); assertEquals("0", Commentary.label(0))
        assertEquals("+5", Commentary.label(5000)); assertEquals("+10", Commentary.label(40_000))
    }

    @Test fun onlyDevicesWithEnoughMemoryGetTheFeature() {
        assertEquals(true, Commentary.supported(2000, false)); assertEquals(false, Commentary.supported(1999, false))
        assertEquals(false, Commentary.supported(4096, true), "low-RAM flag wins")
    }

    @Test fun failuresAreNamedHonestly() {
        assertEquals(Commentary.Failure.OneConnection, Commentary.failureFor(458, false))
        assertEquals(Commentary.Failure.OneConnection, Commentary.failureFor(509, false))
        assertEquals(Commentary.Failure.OneConnection, Commentary.failureFor(0, originalFailedSoon = true), "the first channel dropped when the second started")
        assertEquals(Commentary.Failure.Unreachable, Commentary.failureFor(404, false)); assertEquals(Commentary.Failure.Unreachable, Commentary.failureFor(0, false))
    }
}
