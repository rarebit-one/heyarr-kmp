package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpaceSession] end-to-end against the in-memory node fake: create a space wrapped
 * for this device (+ a recovery recipient), mint changes that the fake accepts only
 * because their content-addressed id matches, fold them back, and prove the causal
 * parents chain. A device with no wrapped copy cannot open the space — the ADR-0049
 * confidentiality gate.
 */
class SpaceSessionTest {
    private val device = FakeDeviceKey(1)
    private val recoveryPub = FakeDeviceKey(9).publicKey()

    private fun session(server: FakeServer, dev: DeviceEncKey = device): SpaceSession {
        var tag = 0
        var writer = 0
        var space = 0
        return SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("tok")),
            device = dev,
            crypto = IdentityCrypto(),
            additionalRecipients = { listOf(recoveryPub) },
            newSpaceId = { "space-${space++}" },
            newTag = { "tag${tag++}" },
            newWriter = { "w${writer++}" },
        )
    }

    @Test
    fun createWrapsForDeviceAndRecovery() {
        val server = FakeServer()
        val id = session(server).createSpace("shared")
        assertEquals(setOf(device.recipientId(), "x25519:" + Hex.encode(recoveryPub)), server.recipients(id))
        assertTrue(session(server).canOpen(id))
    }

    @Test
    fun playlistAddRemoveFoldsAndChainsParents() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")

        assertEquals(listOf("song-a"), s.addToPlaylist(id, "song-a")!!.ids())
        assertEquals(listOf("song-a", "song-b"), s.addToPlaylist(id, "song-b")!!.ids())

        // Re-fold from scratch — the merge is deterministic and node-backed.
        assertEquals(listOf("song-a", "song-b"), s.playlist(id)!!.ids())

        // The second change causally follows the first (its parent is the first head).
        val client = PersonalStateClient(server, server.base, Credential.Session("tok"))
        val changes = client.changes(id)
        assertEquals(2, changes.size)
        val roots = changes.filter { it.parents.isEmpty() }
        val children = changes.filter { it.parents.isNotEmpty() }
        assertEquals(1, roots.size)
        assertEquals(1, children.size)
        assertEquals(listOf(roots[0].changeId), children[0].parents)

        assertEquals(emptyList<String>(), s.removeFromPlaylist(id, "song-a")!!.ids().filter { it == "song-a" })
        assertEquals(listOf("song-b"), s.playlist(id)!!.ids())
    }

    @Test
    fun starredHistoryReadingRoundTrip() {
        val server = FakeServer()
        val s = session(server)
        val star = s.createSpace("personal")
        assertEquals(listOf("m1"), s.star(star, "m1")!!.ids())
        assertEquals(listOf("m2", "m1"), s.star(star, "m2")!!.ids()) // most-recent first
        assertFalse(s.unstar(star, "m1")!!.isStarred("m1"))

        val hist = s.createSpace("personal")
        s.recordPlay(hist, "t1")
        s.recordPlay(hist, "t1")
        s.recordPlay(hist, "t2")
        val log = s.history(hist)!!
        assertEquals("t2", log.nowPlaying())
        assertEquals(2, log.count("t1"))

        val read = s.createSpace("personal")
        s.setReadingPosition(read, "book-1", "epubcfi(/6/4)")
        assertEquals("epubcfi(/6/8)", s.setReadingPosition(read, "book-1", "epubcfi(/6/8)")!!.position("book-1"))
        assertEquals("epubcfi(/6/8)", s.readingPositions(read)!!.position("book-1"))
    }

    @Test
    fun aDeviceWithoutAKeyCannotOpen() {
        val server = FakeServer()
        val owner = session(server)
        val id = owner.createSpace("shared")
        owner.addToPlaylist(id, "secret")

        val stranger = session(server, dev = FakeDeviceKey(2))
        assertFalse(stranger.canOpen(id))
        assertNull(stranger.playlist(id))
        // ...but the owner still reads it.
        assertEquals(listOf("secret"), owner.playlist(id)!!.ids())
    }

    @Test
    fun writesAreIdempotentUnderReFold() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        s.addToPlaylist(id, "x")
        s.addToPlaylist(id, "y")
        assertEquals(2, server.changeCount(id))
        // Folding twice does not create changes; the read is pure.
        s.playlist(id)
        s.playlist(id)
        assertEquals(2, server.changeCount(id))
    }

    @Test
    fun aSpaceWrappedForAPeerMemberIsReadableByThatPeer() {
        val server = FakeServer()
        val peer = FakeDeviceKey(2)
        // The phone mints a space wrapped for itself AND the peer member device.
        val phone = SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("t")),
            device = device,
            crypto = IdentityCrypto(),
            additionalRecipients = { listOf(peer.publicKey()) },
            newSpaceId = { "space-shared" },
            newTag = { "tag0" },
        )
        val id = phone.createSpace("shared")
        phone.addToPlaylist(id, "song")

        assertEquals(setOf(device.recipientId(), peer.recipientId()), server.recipients(id))
        // The peer — a different device — opens and reads the very same playlist.
        val peerSession = SpaceSession(PersonalStateClient(server, server.base, Credential.Session("t")), peer, IdentityCrypto())
        assertTrue(peerSession.canOpen(id))
        assertEquals(listOf("song"), peerSession.playlist(id)!!.ids())
    }

    @Test
    fun parsesX25519Recipients() {
        val pub = FakeDeviceKey(3).publicKey()
        assertTrue(parseX25519Recipient("x25519:" + Hex.encode(pub))!!.contentEquals(pub))
        assertNull(parseX25519Recipient("ed25519:" + Hex.encode(pub))) // wrong algorithm
        assertNull(parseX25519Recipient("x25519:zz")) // not hex / wrong length
        assertNull(parseX25519Recipient(""))
    }
}
