package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [SpaceSession] over a space whose key has rotated (heyarr ADR-0103). A rotation re-encrypts
 * nothing: it wraps a fresh key for the remaining recipients and stores the previous key sealed
 * under it as a history row. So this device opens the space with its copy of the CURRENT key and
 * unrolls the history, reads content sealed under every epoch, writes under the current key only,
 * and — when a rotation lands between its key fetch and its change fetch — re-opens once.
 */
class KeyRotationTest {
    private val device = FakeDeviceKey(1)
    private val crypto = IdentityCrypto()

    private fun session(server: FakeServer): SpaceSession {
        var tag = 0
        return SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("tok")),
            device = device,
            crypto = crypto,
            newSpaceId = { "space-r" },
            newTag = { "tag${tag++}" },
        )
    }

    /** Rotate [spaceId] to a fresh key wrapped for this device; returns the new key. */
    private fun rotate(server: FakeServer, spaceId: String, prev: ByteArray): ByteArray {
        val next = crypto.newSpaceKey()
        server.rotate(
            spaceId,
            mapOf(device.recipientId() to crypto.seal(next, device.publicKey())),
            IdentityCrypto.sealSpaceKey(next, prev),
        )
        return next
    }

    private fun currentKey(server: FakeServer, spaceId: String): ByteArray =
        crypto.unwrap(server.wrapped(spaceId, device.recipientId()), device.seed())

    @Test
    fun aRotatedSpaceReadsEveryEpochAndWritesUnderTheCurrentKey() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        s.addToPlaylist(id, "before")
        val k0 = currentKey(server, id)
        val k1 = rotate(server, id, k0)
        s.addToPlaylist(id, "between")
        val k2 = rotate(server, id, k1)

        assertEquals(listOf("before", "between"), s.playlist(id)!!.ids())
        s.addToPlaylist(id, "after")
        assertEquals(listOf("before", "between", "after"), s.playlist(id)!!.ids())

        // Each change is under the key of the epoch it was written at; the newest under k2 only.
        val prefixes = server.changes(id).map { it.ciphertext.copyOfRange(0, 4).toList() }
        assertEquals(listOf(k0, k1, k2).map { it.copyOfRange(0, 4).toList() }, prefixes)
    }

    @Test
    fun aKeyFetchThatRacedARotationReOpensOnce() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        s.addToPlaylist(id, "before")
        val atEpoch0 = server.keysSnapshot(id)
        rotate(server, id, currentKey(server, id))
        s.addToPlaylist(id, "after") // a write under the new key

        // The next read's key fetch still sees epoch 0, but the log holds an epoch-1 change.
        server.serveKeysOnce(id, atEpoch0)
        val gets = server.keysGets
        assertEquals(listOf("before", "after"), s.playlist(id)!!.ids())
        assertEquals("the stale open, then one re-open", 2, server.keysGets - gets)
    }

    @Test
    fun anIncompleteHistoryCannotOpen() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        rotate(server, id, currentKey(server, id))
        server.dropHistory(id)
        assertFalse(s.canOpen(id))
        assertNull(s.playlist(id))
    }

    @Test
    fun aSupersededCopyCannotOpen() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        val atEpoch0 = server.keysSnapshot(id)
        rotate(server, id, currentKey(server, id))
        // The node serving this device's epoch-0 copy while the space is at epoch 1.
        // Served for each open AND its one retry (two opens below): a persistent disagreement.
        server.serveKeysOnce(id, atEpoch0.replace("\"key_epoch\":0", "\"key_epoch\":1"), times = 4)
        assertFalse(s.canOpen(id))
        assertEquals(SpaceSession.OpenState.UNREADABLE, s.openState(id))
    }

    @Test
    fun aRotationWithNoInboundBlobStillMovesTheNextWriteToTheNewKey() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        s.addToPlaylist(id, "before")
        val atEpoch0 = server.keysSnapshot(id)
        val k1 = rotate(server, id, currentKey(server, id))

        // This operation opens on keys from before the rotation; nothing under k1 exists yet, so
        // no decrypt misses. The pre-write epoch check must still move the write to k1.
        server.serveKeysOnce(id, atEpoch0)
        s.addToPlaylist(id, "after")
        assertEquals(k1.copyOfRange(0, 4).toList(), server.changes(id).last().ciphertext.copyOfRange(0, 4).toList())
        assertEquals(listOf("before", "after"), s.playlist(id)!!.ids())
    }

    @Test
    fun aHistoryRowNewerThanTheFetchedKeysStillOpens() {
        val server = FakeServer()
        val s = session(server)
        val id = s.createSpace("shared")
        s.addToPlaylist(id, "before")
        val k1 = rotate(server, id, currentKey(server, id))
        val atEpoch1 = server.keysSnapshot(id)
        rotate(server, id, k1)
        // GET /keys saw epoch 1; the history (fetched after) already holds row 2.
        server.serveKeysOnce(id, atEpoch1)
        assertEquals(SpaceSession.OpenState.OPEN, s.openState(id))
    }
}
