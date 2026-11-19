package one.rarebit.heyarr.mobile.cast

import one.rarebit.heyarr.mobile.playback.PlaybackClient.PlanResult
import one.rarebit.heyarr.mobile.playback.PlaybackJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** The Cast-profile plan and the direct-or-relay decision rule (heyarr-kmp#250). */
class CastRoutingTest {
    private fun v4(text: String) = InetAddress.getByName(text) as Inet4Address

    private val stream = CastSource("https://node.example/api/v1/playback/stream/tok", "video/mp4", 7200.0)
    private val free = stream.copy(credentialFree = true)

    @Test fun theCastProfileAsksForH264AndForcesStereo() {
        val body = CastRouting.PROFILE.planRequestBody("a1")
        assertEquals(
            "MP4, H.264, no audio codec (so the node always encodes stereo AAC), 1080 lines",
            """{"asset_id":"a1","client":{"containers":["mp4"],"video":["h264"],"audio":[],"max_height":1080}}""",
            body,
        )
    }

    @Test fun onlyTheRepackagedMp4StreamIsCastSafe() {
        val source = PlaybackJson.Source("mkv", "hevc", "eac3", 3840, 2160, 7200.0)
        val cast = CastRouting.sourceOf(PlanResult.Stream(stream.url, "video/mp4", "transcode", source))
        assertEquals(stream, cast)
        assertFalse("today's stream token needs this phone's credential", cast.credentialFree)
        assertTrue(cast.restartSeekable)
        for (refused in listOf(
            PlanResult.Direct(null, "plays as it is"),
            PlanResult.Unavailable("node predates the plan contract (HTTP 400)"),
            PlanResult.Stream(stream.url, "video/webm", null, null),
        )) {
            try {
                CastRouting.sourceOf(refused)
                fail("cast $refused")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!, expected.message!!.isNotBlank())
            }
        }
    }

    @Test fun aSourceThatNeedsACredentialIsAlwaysRelayed() {
        var resolved = false
        val route = CastRouting.decide(stream, v4("192.168.1.50")) {
            resolved = true
            v4("192.168.1.5")
        }
        assertTrue(route is CastRoute.Relay)
        assertFalse("nothing to decide, so nothing is resolved", resolved)
    }

    @Test fun aCredentialFreeSourceGoesDirectOnlyBetweenPrivateNetworks() {
        assertTrue(CastRouting.decide(free, v4("192.168.1.50")) { v4("10.1.2.3") } is CastRoute.Direct)
        assertTrue(CastRouting.decide(free, v4("172.20.0.9")) { v4("192.168.7.1") } is CastRoute.Direct)
        assertTrue(
            "a node at a public address",
            CastRouting.decide(free, v4("192.168.1.50")) {
                v4("203.0.113.7")
            } is CastRoute.Relay,
        )
        assertTrue(
            "a node over a tailnet (CGNAT)",
            CastRouting.decide(free, v4("192.168.1.50")) {
                v4("100.101.102.103")
            } is CastRoute.Relay,
        )
        assertTrue("an unresolvable node", CastRouting.decide(free, v4("192.168.1.50")) { null } is CastRoute.Relay)
        assertTrue(
            "a TV on a public network",
            CastRouting.decide(free, v4("198.51.100.4")) {
                v4("10.0.0.2")
            } is CastRoute.Relay,
        )
        assertTrue(
            "a TV whose address is unknown",
            CastRouting.decide(free, null) {
                v4("10.0.0.2")
            } is CastRoute.Relay,
        )
    }

    @Test fun privateMeansRfc1918() {
        for (yes in listOf("10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1")) {
            assertTrue(yes, CastRouting.privateV4(v4(yes)))
        }
        for (no in listOf(
            "172.15.0.1",
            "172.32.0.1",
            "192.169.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "100.64.0.1",
            "8.8.8.8",
        )) {
            assertFalse(no, CastRouting.privateV4(v4(no)))
        }
        assertFalse("IPv6 is not routed direct", CastRouting.privateV4(InetAddress.getByName("fd00::1")))
        assertFalse(CastRouting.privateV4(null))
    }
}
