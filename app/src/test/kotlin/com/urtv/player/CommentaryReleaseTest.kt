package com.urtv.player

import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The second (audio) player exists only while the merge is on: every way out releases it completely. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UrtvApp::class)
class CommentaryReleaseTest {
    private val app get() = ApplicationProvider.getApplicationContext<UrtvApp>()
    private fun ch(n: Int) = com.urtv.player.core.Channel("c$n", "قناة $n", "http://127.0.0.1:1/s/$n.m3u8")

    private fun on() { app.commentary.start(ch(9)); assertEquals(true, app.commentary.audioPlayerAlive, "the second player exists while the merge is on") }

    @Test fun noSecondPlayerUntilTheMergeIsStarted() { assertEquals(false, app.commentary.audioPlayerAlive) }

    @Test fun choosingTheChannelSoundReleasesIt() { on(); app.commentary.original(); assertEquals(false, app.commentary.audioPlayerAlive); assertEquals(false, app.player.commentaryMuted) }

    @Test fun changingChannelReleasesIt() { on(); app.player.play(ch(1)); assertEquals(false, app.commentary.audioPlayerAlive); assertEquals(false, app.player.commentaryMuted) }

    @Test fun closingThePlayerReleasesIt() { on(); app.player.stop(); assertEquals(false, app.commentary.audioPlayerAlive) }

    @Test fun theAppGoingToTheBackgroundReleasesIt() { on(); app.player.release(); assertEquals(false, app.commentary.audioPlayerAlive) }

    @Test fun aFailureReleasesItAndBringsTheOriginalSoundBack() {
        on(); app.commentary.press(500)
        app.commentary.stop()
        assertEquals(false, app.commentary.audioPlayerAlive); assertEquals(false, app.player.commentaryMuted)
    }

    @Test fun startingAnotherChannelForTheSoundNeverLeavesTwoPlayers() { on(); app.commentary.start(ch(8)); assertEquals(true, app.commentary.audioPlayerAlive); app.commentary.stop(); assertEquals(false, app.commentary.audioPlayerAlive) }
}
