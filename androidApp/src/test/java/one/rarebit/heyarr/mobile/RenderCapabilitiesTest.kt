package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.RenderCapabilitiesJson
import one.rarebit.heyarr.mobile.playback.RenderCapabilityCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audio queue streams locked-screen tracks from render capabilities (heyarr-core
 * ADR-0112): minted when the queue starts, swapped in per track as it loads, and never in
 * the way of a track the node would not mint for.
 */
class RenderCapabilitiesTest {

    private val base = "http://node.lan:7777"
    private val now = 1_800_000_000_000L
    private val sixHours = 6 * 60 * 60 * 1000L

    private fun track(id: String) = AudioItem(
        assetId = id,
        workId = "w-$id",
        title = id,
        contentUrl = "$base/api/v1/blobs/hash-$id/content",
    )

    @Test fun parsesUrlsAndReasons() {
        // One line of JSON, split only for the line limit (JsonScan fixtures stay single-line).
        val body = """{"capabilities":[{"asset_id":"a1",""" +
            """"render_url":"https://heyarr.example/render/v1.x/stream.mp3","expires_at":"2027-01-15T06:00:00Z"},""" +
            """{"asset_id":"a2","render_unavailable":"the replica is on another peer"}]}"""
        val entries = RenderCapabilitiesJson.parse(body)
        assertEquals(2, entries.size)
        assertEquals("https://heyarr.example/render/v1.x/stream.mp3", entries[0].renderUrl)
        assertEquals(java.time.Instant.parse("2027-01-15T06:00:00Z").toEpochMilli(), entries[0].expiresAtMs)
        assertNull(entries[1].renderUrl)
        assertEquals("the replica is on another peer", entries[1].unavailable)
    }

    @Test fun rebasesOntoTheOriginThePhoneUses() {
        assertEquals(
            "the public hostname is swapped for the base the phone reached the node by",
            "$base/render/v1.abc/stream.mp3",
            RenderCapabilityCache.rebase(
                "https://heyarr.example/render/v1.abc/stream.mp3",
                "$base/api/v1/blobs/h/content",
            ),
        )
        assertNull(
            "anything that is not a /render/ path is refused",
            RenderCapabilityCache.rebase("https://heyarr.example/api/v1/blobs/h/content", "$base/x"),
        )
    }

    @Test fun mintedTracksResolveAndTheRestStayOnTheBlobUrl() {
        val clock = longArrayOf(now)
        val cache = RenderCapabilityCache { clock[0] }
        cache.use("user-a") { ids ->
            ids.map { id ->
                if (id == "a2") {
                    RenderCapabilitiesJson.Entry(id, null, "another peer", null)
                } else {
                    RenderCapabilitiesJson.Entry(
                        id,
                        "https://pub.example/render/cap-$id/stream.mp3",
                        null,
                        now + sixHours,
                    )
                }
            }
        }
        val a1 = track("a1")
        val a2 = track("a2")
        cache.prefetch(listOf(a1, a2))

        assertEquals("$base/render/cap-a1/stream.mp3", cache.resolve(a1.contentUrl))
        assertNull("a refused track plays from its blob URL", cache.resolve(a2.contentUrl))
        assertNull("an unknown URL is left alone", cache.resolve("$base/api/v1/blobs/other/content"))

        clock[0] = now + sixHours - RenderCapabilityCache.MIN_REMAINING_MS
        assertNull("a capability near expiry is not opened", cache.resolve(a1.contentUrl))
    }

    @Test fun aFailedMintOrNoMinterChangesNothing() {
        val cache = RenderCapabilityCache { now }
        val a1 = track("a1")
        cache.prefetch(listOf(a1))
        assertNull("no minter (guest, signed out): nothing cached", cache.resolve(a1.contentUrl))

        cache.use("user-a") { throw java.io.IOException("node too old: 404") }
        cache.prefetch(listOf(a1))
        assertNull("a failed mint leaves the blob URL in charge", cache.resolve(a1.contentUrl))
    }

    @Test fun aLongQueueIsMintedInPages() {
        val pages = mutableListOf<Int>()
        val cache = RenderCapabilityCache { now }
        cache.use("user-a") { ids ->
            pages += ids.size
            ids.map { RenderCapabilitiesJson.Entry(it, "https://pub.example/render/c-$it/s", null, now + sixHours) }
        }
        cache.prefetch((1..450).map { track("t$it") })
        assertEquals(listOf(200, 200, 50), pages)
    }

    @Test fun aNewSessionForgetsTheLastOnesCapabilities() {
        val cache = RenderCapabilityCache { now }
        cache.use("user-a") { ids ->
            ids.map {
                RenderCapabilitiesJson.Entry(
                    it,
                    "https://pub.example/render/c-$it/s",
                    null,
                    now + sixHours,
                )
            }
        }
        val a1 = track("a1")
        cache.prefetch(listOf(a1))
        assertEquals("$base/render/c-a1/s", cache.resolve(a1.contentUrl))

        // The same session coming back (rotation, the app rebuilt behind a playing queue) keeps them.
        cache.use("user-a") { emptyList() }
        assertEquals("$base/render/c-a1/s", cache.resolve(a1.contentUrl))

        // Signing out (or becoming a guest) must not leave unrevocable URLs serving.
        cache.use("guest", null)
        assertNull("a signed-out phone streams nothing it was minted before", cache.resolve(a1.contentUrl))

        // ...but a guest moment while the same device credential is restored loses nothing.
        cache.use("user-a") { emptyList() }
        assertEquals("$base/render/c-a1/s", cache.resolve(a1.contentUrl))

        // A different signed-in user starts clean, and the old user's URLs are gone for good.
        cache.use("user-b") { emptyList() }
        assertNull(cache.resolve(a1.contentUrl))
        cache.use("user-a") { emptyList() }
        assertNull("user-b's sign-in forgot user-a's capabilities", cache.resolve(a1.contentUrl))
    }

    @Test fun aMintThatFinishesAfterTheSessionChangedIsDiscarded() {
        val cache = RenderCapabilityCache { now }
        cache.use("user-a") { ids ->
            // The user signs out while this request is in flight.
            cache.use("guest", null)
            ids.map { RenderCapabilitiesJson.Entry(it, "https://pub.example/render/c-$it/s", null, now + sixHours) }
        }
        val a1 = track("a1")
        cache.prefetch(listOf(a1))
        assertNull("the old session's late answer is not written back", cache.resolve(a1.contentUrl))
    }

    @Test fun theSessionKeyIgnoresTheProofButNotTheUser() {
        val a = one.rarebit.heyarr.core.auth.Credential.Device("cert-a", "proof-1")
        val aLater = one.rarebit.heyarr.core.auth.Credential.Device("cert-a", "proof-2")
        val b = one.rarebit.heyarr.core.auth.Credential.Device("cert-b", "proof-1")
        assertEquals(RenderCapabilityCache.sessionKey(base, a), RenderCapabilityCache.sessionKey(base, aLater))
        org.junit.Assert.assertNotEquals(
            RenderCapabilityCache.sessionKey(base, a),
            RenderCapabilityCache.sessionKey(base, b),
        )
        org.junit.Assert.assertNotEquals(
            RenderCapabilityCache.sessionKey(base, a),
            RenderCapabilityCache.sessionKey(base, one.rarebit.heyarr.core.auth.Credential.Guest),
        )
    }

    @Test fun aQueueIsRenewedBeforeItsCapabilitiesLapse() {
        val clock = longArrayOf(now)
        val cache = RenderCapabilityCache { clock[0] }
        cache.use("user-a") { ids ->
            ids.filter { it != "a2" }.map {
                RenderCapabilitiesJson.Entry(it, "https://pub.example/render/c-$it/s", null, clock[0] + sixHours)
            }
        }
        val queue = listOf(track("a1"), track("a2"))
        cache.prefetch(queue)
        assertFalse("fresh capabilities need nothing", cache.needsRenewal(queue))

        clock[0] = now + sixHours - RenderCapabilityCache.RENEW_WITHIN_MS + 1
        assertTrue("under two hours left: renew while the phone can sign", cache.needsRenewal(queue))
        cache.prefetch(queue)
        assertFalse("renewed for another six hours", cache.needsRenewal(queue))
        assertEquals("$base/render/c-a1/s", cache.resolve(queue[0].contentUrl))

        cache.use("guest", null)
        assertFalse("a guest has nothing to renew", cache.needsRenewal(queue))
    }
}
