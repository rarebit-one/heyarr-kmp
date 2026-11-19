package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.playback.HlsPresentation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HlsPresentationTest {
    @Test
    fun onlyThePlaylistTheInitAndNumberedSegmentsPassUnderTheCapability() {
        for (name in listOf("index.m3u8", "init.mp4", "0.m4s", "1.m4s", "1234.m4s")) {
            assertTrue(HlsPresentation.isEntry(name), name)
        }
        // heyarr-core's route takes `^(0|[1-9][0-9]*)\.m4s$`: no leading zeros, no signs, no
        // traversal, nothing else a relay could be talked into fetching from the node.
        for (name in listOf("", "01.m4s", "-1.m4s", "1.m4s?x", "../index.m3u8", "x.m4s", "index.m3u8/x", "1.ts")) {
            assertFalse(HlsPresentation.isEntry(name), name)
        }
    }

    @Test
    fun aPlaylistPathIsOneCapabilityUnderTheRenderMount() {
        assertTrue(HlsPresentation.isPlaylistPath("/render/hls/h1.abc-DEF_9/index.m3u8"))
        assertFalse(HlsPresentation.isPlaylistPath("/render/hls/a/b/index.m3u8"))
        assertFalse(HlsPresentation.isPlaylistPath("/render/hls//index.m3u8"))
        assertFalse(HlsPresentation.isPlaylistPath("/render/hls/cap/0.m4s"))
        assertFalse(HlsPresentation.isPlaylistPath("/api/v1/playback/stream/token"))
    }

    @Test
    fun entriesResolveAgainstThePlaylistsCapabilityPrefix() {
        assertEquals(
            "http://node:8080/render/hls/cap/",
            HlsPresentation.prefixOf("http://node:8080/render/hls/cap/index.m3u8"),
        )
        assertFailsWith<IllegalArgumentException> { HlsPresentation.prefixOf("http://node/render/hls/cap/0.m4s") }
    }
}
