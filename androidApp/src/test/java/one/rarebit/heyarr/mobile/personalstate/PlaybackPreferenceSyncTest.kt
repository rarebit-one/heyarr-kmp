package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.core.playback.QualityPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The person's playback default in personal state (heyarr-core#887): a last-writer-wins
 * register in a preferences role space, found by a second device that never recorded it,
 * against the in-memory node fake (a real fold of decrypted changes, not a mock).
 */
class PlaybackPreferenceSyncTest {
    private val server = FakeServer()
    private var space = 0
    private var writer = 0

    /** One of the person's devices: its own role registry, the same node and key. */
    private fun device(registry: SpaceRegistry = InMemorySpaceRegistry()): PersonalStateCoordinator =
        PersonalStateCoordinator(session(), registry)

    private fun session(): SpaceSession {
        var tag = 0
        return SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("t")),
            device = FakeDeviceKey(1),
            crypto = IdentityCrypto(),
            newSpaceId = { "space-${space++}" },
            newTag = { "tag${tag++}" },
            newWriter = { "w${writer++}" },
        )
    }

    @Test fun theDefaultRoundTripsAndTheLastWriteWins() {
        val registry = InMemorySpaceRegistry()
        val phone = device(registry)
        assertNull("nothing set yet", phone.playbackPreference())

        phone.setPlaybackPreference(PlaybackPreference(QualityPreference.BEST, maxHeight = 1080))
        phone.setPlaybackPreference(PlaybackPreference(QualityPreference.DATA_SAVER))
        assertEquals(PlaybackPreference(QualityPreference.DATA_SAVER), phone.playbackPreference())
        assertFalse(
            "the preferences space is a role, never a playlist",
            phone.playlists().any { it.spaceId == registry.preferencesSpace() },
        )
    }

    @Test fun anotherDeviceFindsTheSpaceByWhatItHolds() {
        // Each device minted its own starred space, so there is more than one personal space.
        val phone = device()
        phone.setStarred("m1", true)
        phone.setPlaybackPreference(PlaybackPreference(QualityPreference.BEST))

        val tvRegistry = InMemorySpaceRegistry()
        val tv = device(tvRegistry)
        tv.setStarred("m2", true)
        assertEquals(
            "the television reads the phone's default",
            PlaybackPreference(QualityPreference.BEST),
            tv.playbackPreference(),
        )
        val adopted = tvRegistry.preferencesSpace()

        tv.setPlaybackPreference(PlaybackPreference(QualityPreference.FIT, maxHeight = 2160))
        assertEquals("…and writes to the same space, not one of its own", adopted, tvRegistry.preferencesSpace())
        assertEquals(PlaybackPreference(QualityPreference.FIT, maxHeight = 2160), phone.playbackPreference())
    }

    @Test fun twoDevicesThatEachMintedASpaceSettleOnTheLowestId() {
        // Both set a default before either could see the other's space (Codex P2 on
        // heyarr-kmp#245): the phone's space-0 and the television's space-1.
        val raw = session()
        val phoneSpace = raw.createSpace("personal")
        raw.setReadingPosition(phoneSpace, PLAYBACK_KEY, PlaybackPreference(QualityPreference.DATA_SAVER).planJson())
        val tvSpace = raw.createSpace("personal")
        raw.setReadingPosition(tvSpace, PLAYBACK_KEY, PlaybackPreference(QualityPreference.BEST).planJson())
        val phoneRegistry = InMemorySpaceRegistry().apply { setPreferencesSpace(phoneSpace) }
        val tvRegistry = InMemorySpaceRegistry().apply { setPreferencesSpace(tvSpace) }
        val phone = device(phoneRegistry)
        val tv = device(tvRegistry)

        assertEquals(
            "the television gives up its own space for the lowest id, value and all",
            PlaybackPreference(QualityPreference.DATA_SAVER),
            tv.playbackPreference(),
        )
        assertEquals(phoneSpace, tvRegistry.preferencesSpace())
        tv.setPlaybackPreference(PlaybackPreference(QualityPreference.FIT, maxHeight = 1080))
        assertEquals(PlaybackPreference(QualityPreference.FIT, maxHeight = 1080), phone.playbackPreference())
        assertEquals("the phone kept its space", phoneSpace, phoneRegistry.preferencesSpace())
    }
}
