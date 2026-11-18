package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.ClientCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCapabilitiesTest {

    @Test fun foldsDecoderMimesIntoShortNamesDedupedInOrder() {
        val caps = ClientCapabilities.fromDecoderMimes(
            listOf(
                "video/avc", "audio/mp4a-latm", "video/hevc", "video/avc", // a second h264 decoder
                "audio/mpeg", "audio/opus", "audio/flac", "video/x-vnd.on2.vp9", "video/av01",
                "audio/raw", "audio/g711-alaw", "video/x-weird",
            ),
            maxHeight = 2412,
        )
        assertEquals(listOf("h264", "hevc", "vp9", "av1"), caps.video)
        assertEquals(listOf("aac", "mp3", "opus", "flac", "pcm"), caps.audio)
        assertEquals(2412, caps.maxHeight)
        assertEquals(listOf("mp4", "mkv", "webm"), caps.containers)
    }

    @Test fun ac3IsNamedWhenADecoderExistsAndAbsentWhenNot() {
        assertEquals("ac3", ClientCapabilities.codecName("audio/ac3"))
        assertEquals("eac3", ClientCapabilities.codecName("audio/eac3"))
        assertNull(ClientCapabilities.codecName("audio/g711-mlaw"))
        val caps = ClientCapabilities.fromDecoderMimes(listOf("video/avc", "audio/mp4a-latm"), maxHeight = 1080)
        assertEquals(listOf("aac"), caps.audio)
    }

    @Test fun planRequestBodyShape() {
        val caps = ClientCapabilities(video = listOf("h264"), audio = listOf("aac", "opus"), maxHeight = 1080)
        assertEquals(
            """{"asset_id":"a\"b","client":{"containers":["mp4","mkv","webm"],"video":["h264"],"audio":["aac","opus"],"max_height":1080}}""",
            caps.planRequestBody("a\"b"),
        )
    }

    @Test fun hdrChoosesACopyButIsNotSentToANodeThatMayRefuseIt() {
        // A node before heyarr-core#885 may 400 an unknown field, and a 400 means "plan nothing".
        val caps =
            ClientCapabilities(video = listOf("hevc"), audio = listOf("aac"), maxHeight = 1080, supportsHdr = true)
        assertEquals("the display is the probed screen", 1080, caps.display.height)
        assertTrue("the display is the probed screen", caps.display.supportsHdr)
        assertFalse("supports_hdr is not sent yet", caps.planRequestBody("a").contains("hdr"))
        assertTrue(
            "the declared cap is sent",
            caps.forMaxHeight(720).planRequestBody("a").contains("\"max_height\":720"),
        )
    }

    @Test fun planByWorkBodyShape() {
        val caps =
            ClientCapabilities(video = listOf("hevc"), audio = listOf("aac"), maxHeight = 1080, supportsHdr = true)
        assertEquals(
            """{"work_id":"w1","client":{"containers":["mp4","mkv","webm"],"video":["hevc"],"audio":["aac"],""" +
                """"max_height":1080,"supports_hdr":true}}""",
            caps.planByWorkBody("w1"),
        )
    }

    @Test fun withoutStrikesOneCodecOff() {
        val caps = ClientCapabilities(video = listOf("h264"), audio = listOf("aac", "ac3"), maxHeight = 1080)
        assertEquals(listOf("aac"), caps.without("ac3").audio)
        assertEquals(listOf("h264"), caps.without("ac3").video)
        assertEquals(caps, caps.without("nope"))
    }
}
