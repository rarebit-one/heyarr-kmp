package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app-facing façade over the engine: playlists exclude the role spaces (the
 * gateway's convention), a star creates the starred role space lazily and reflects
 * in the starred ids, and history/reading round-trip — all against the in-memory
 * node fake, so this is a real folded-and-decrypted assertion, not a mock.
 */
class PersonalStateCoordinatorTest {
    private val registry = InMemorySpaceRegistry()
    private val server = FakeServer()
    private val coordinator = run {
        var space = 0
        var tag = 0
        var writer = 0
        val session = SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("t")),
            device = FakeDeviceKey(1),
            crypto = IdentityCrypto(),
            newSpaceId = { "space-${space++}" },
            newTag = { "tag${tag++}" },
            newWriter = { "w${writer++}" },
        )
        PersonalStateCoordinator(session, registry)
    }

    @Test
    fun createAddAndListPlaylists() {
        val id = coordinator.createPlaylist("Roadtrip")
        coordinator.addToPlaylist(id, "song-a")
        coordinator.addToPlaylist(id, "song-b")

        val playlists = coordinator.playlists()
        assertEquals(1, playlists.size)
        assertEquals("Roadtrip", playlists[0].name)
        assertEquals(listOf("song-a", "song-b"), playlists[0].itemIds)

        coordinator.removeFromPlaylist(id, "song-a")
        assertEquals(listOf("song-b"), coordinator.playlist(id)!!.itemIds)
    }

    @Test
    fun starringCreatesTheRoleSpaceAndExcludesItFromPlaylists() {
        val playlist = coordinator.createPlaylist(null)
        coordinator.addToPlaylist(playlist, "song-a")

        assertEquals(listOf("m1"), coordinator.setStarred("m1", true))
        // The starred space now exists and is NOT listed as a playlist.
        assertEquals(listOf(playlist), coordinator.playlists().map { it.spaceId })
        assertEquals(listOf("m1"), coordinator.starredIds())

        assertFalse(coordinator.setStarred("m1", false).contains("m1"))
        assertTrue(coordinator.starredIds().isEmpty())
    }

    @Test
    fun historyAndReadingRoundTrip() {
        coordinator.recordPlay("t1")
        coordinator.recordPlay("t2")
        assertEquals(listOf("t2", "t1"), coordinator.recentlyPlayedIds())

        coordinator.setReadingPosition("book-1", "epubcfi(/6/4)")
        coordinator.setReadingPosition("book-1", "epubcfi(/6/8)")
        assertEquals("epubcfi(/6/8)", coordinator.readingPosition("book-1"))
    }

    @Test
    fun anExistingRoleSpaceThatWillNotOpenIsNeverReplaced() {
        coordinator.setStarred("m1", true)
        val starred = registry.starredSpace()!!
        val crypto = IdentityCrypto()
        val device = FakeDeviceKey(1)
        // A rotation whose history row went missing: this device has a copy but cannot unroll.
        val prev = crypto.unwrap(server.wrapped(starred, device.recipientId()), device.seed())
        val next = crypto.newSpaceKey()
        server.rotate(
            starred,
            mapOf(device.recipientId() to crypto.seal(next, device.publicKey())),
            IdentityCrypto.sealSpaceKey(next, prev),
        )
        server.dropHistory(starred)
        val spaces = server.spaceCount()

        assertThrows(SpaceUnreadableException::class.java) { coordinator.setStarred("m2", true) }
        assertEquals("no replacement space minted", spaces, server.spaceCount())
        assertEquals(starred, registry.starredSpace())
    }
}
