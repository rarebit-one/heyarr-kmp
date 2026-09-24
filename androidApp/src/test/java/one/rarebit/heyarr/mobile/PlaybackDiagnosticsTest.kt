package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.playback.PlaybackDiagnostics
import one.rarebit.heyarr.mobile.playback.PlaybackDiagnostics.TrackGroup
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {

    private fun target(origin: PlaybackTarget.Origin, mime: String? = "video/mp4") = PlaybackTarget(
        contentUrl = "https://h/x",
        credential = Credential.Session("t"),
        isVideo = true,
        mimeType = mime,
        origin = origin,
    )

    private val h264 = TrackGroup(PlaybackDiagnostics.TYPE_VIDEO, supported = true, sampleMime = "video/avc")
    private val ac3 = TrackGroup(PlaybackDiagnostics.TYPE_AUDIO, supported = false, sampleMime = "audio/ac3", channels = 6)
    private val aac = TrackGroup(PlaybackDiagnostics.TYPE_AUDIO, supported = true, sampleMime = "audio/mp4a-latm", channels = 2)

    @Test fun yellowstoneAc3FiveOneOnAPlannedDirectTargetAsksForAStream() {
        val issue = PlaybackDiagnostics.assess(listOf(h264, ac3), target(PlaybackTarget.Origin.DIRECT_PLANNED))!!
        assertEquals(
            "Audio is AC-3 5.1, which this phone can't decode — asking the server for a phone-friendly stream",
            issue.message,
        )
        assertEquals(PlaybackDiagnostics.TYPE_AUDIO, issue.type)
        assertEquals("ac3", issue.codec)
    }

    @Test fun onAnOlderNodeTheBannerSaysNotAvailableYet() {
        val issue = PlaybackDiagnostics.assess(listOf(h264, ac3), target(PlaybackTarget.Origin.DIRECT_UNPLANNED))!!
        assertTrue(issue.message.endsWith("a phone-friendly stream from the server is not available yet"))
    }

    @Test fun oneSupportedAudioGroupIsEnough() {
        assertNull(PlaybackDiagnostics.assess(listOf(h264, ac3, aac), target(PlaybackTarget.Origin.DIRECT_PLANNED)))
    }

    @Test fun noTracksYetIsNoIssue() {
        assertNull(PlaybackDiagnostics.assess(emptyList(), target(PlaybackTarget.Origin.DIRECT_PLANNED)))
    }

    @Test fun unsupportedVideoWinsOverUnsupportedAudio() {
        val hevc = TrackGroup(PlaybackDiagnostics.TYPE_VIDEO, supported = false, sampleMime = "video/hevc")
        val issue = PlaybackDiagnostics.assess(listOf(hevc, ac3), target(PlaybackTarget.Origin.DIRECT_PLANNED))!!
        assertTrue(issue.message.startsWith("Video is HEVC (H.265), which this phone can't decode"))
        assertEquals("hevc", issue.codec)
    }

    @Test fun afterAFailedReplanTheBannerStopsPromising() {
        val issue = PlaybackDiagnostics.assess(listOf(h264, ac3), target(PlaybackTarget.Origin.DIRECT_PLANNED))!!
        assertEquals(
            "Audio is AC-3 5.1, which this phone can't decode — the server offered no phone-friendly stream for it.",
            PlaybackDiagnostics.afterReplanFailed(issue),
        )
    }

    @Test fun aviNoFrameMessageNamesTheContainer() {
        val msg = PlaybackDiagnostics.noFrameMessage(target(PlaybackTarget.Origin.DIRECT_UNPLANNED, "video/x-msvideo"))
        assertTrue(msg.startsWith("This AVI file isn't producing any picture on this phone"))
        assertTrue(msg.endsWith("not available yet"))
    }

    @Test fun decoderInitFailureIsNamedNotBlack() {
        val msg = PlaybackDiagnostics.describeError("ERROR_CODE_DECODER_INIT_FAILED", "Decoder init failed: OMX.x", target(PlaybackTarget.Origin.DIRECT_PLANNED))
        assertTrue(msg.startsWith("This phone has no working decoder for this file — asking the server for a phone-friendly stream"))
        assertTrue(msg.contains("ERROR_CODE_DECODER_INIT_FAILED: Decoder init failed: OMX.x"))
    }

    @Test fun httpFailureIsNamed() {
        val msg = PlaybackDiagnostics.describeError("ERROR_CODE_IO_BAD_HTTP_STATUS", null, target(PlaybackTarget.Origin.STREAM))
        assertEquals("The node refused the stream (HTTP error).\nERROR_CODE_IO_BAD_HTTP_STATUS", msg)
    }
}
