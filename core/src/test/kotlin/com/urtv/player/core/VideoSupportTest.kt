package com.urtv.player.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoSupportTest {
    private fun t(m: String?, w: Int, h: Int, sel: Boolean) = VideoSupport.Track(m, w, h, sel)

    @Test fun aVideoTrackNobodyCanPlayIsReportedInsteadOfSilentSound() {
        val b = VideoSupport.blocked(listOf(t("video/hevc", 3840, 2160, false)))
        assertEquals(3840, b?.width)
        assertEquals("4K HEVC", VideoSupport.label(b!!.mime, b.width, b.height))
    }

    @Test fun nothingIsReportedWhenAPictureIsPlayingOrThereIsNoVideo() {
        assertNull(VideoSupport.blocked(listOf(t("video/avc", 1920, 1080, true), t("video/hevc", 3840, 2160, false))), "a lower variant plays: fine")
        assertNull(VideoSupport.blocked(emptyList()), "a radio stream has no video track")
    }

    @Test fun theBiggestUnplayableTrackIsNamed() {
        assertEquals(2160, VideoSupport.blocked(listOf(t("video/hevc", 1920, 1080, false), t("video/hevc", 3840, 2160, false)))?.height)
    }

    @Test fun labelsAndReportTokens() {
        assertEquals("1080p H.264", VideoSupport.label("video/avc", 1920, 1080)); assertEquals("720p", VideoSupport.label(null, 1280, 720)); assertEquals("", VideoSupport.label(null, 0, 0))
        assertEquals("no_picture_4K_HEVC_3840x2160_decoders0", VideoSupport.reportToken("video/hevc", 3840, 2160, 0))
    }
}
