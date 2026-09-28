package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.vault.PersonalStateId
import one.rarebit.heyarr.core.vault.SnapshotEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The snapshot a device folds from is checked against what it was sealed with (heyarr-core#681).
 * A snapshot's frontier travels beside its ciphertext and its id is a public digest, so a `write`
 * token with no space key can relabel a valid snapshot, or present another record's ciphertext as
 * one, and the node — which cannot decrypt — stores it. The device must refuse both rather than
 * fold a state under a causal point it was never taken at.
 */
class SnapshotTrustTest {
    private val device = FakeDeviceKey(1)
    private val crypto = IdentityCrypto()

    private class Fixture(val server: FakeServer, val session: SpaceSession, val spaceId: String, val key: ByteArray)

    /** A space holding two adds, with this device's key recovered for building snapshots. */
    private fun fixture(): Fixture {
        val server = FakeServer()
        var n = 0
        val session = SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("tok")),
            device = device,
            crypto = crypto,
            newSpaceId = { "space-${n++}" },
            newTag = { "tag${n++}" },
        )
        val id = session.createSpace("shared")
        session.addToPlaylist(id, "song-a")
        session.addToPlaylist(id, "song-b")
        val wrapped = PersonalStateClient(server, server.base, Credential.Session("tok")).wrappedKeys(id)
            .first { it.recipient == device.recipientId() }.wrapped
        return Fixture(server, session, id, crypto.unwrap(wrapped, device.seed()))
    }

    private fun snapshot(spaceId: String, frontier: List<String>, ciphertext: ByteArray): EncryptedSnapshot {
        val heads = PersonalStateId.canonical(frontier)
        return EncryptedSnapshot(spaceId, PersonalStateId.snapshotId(spaceId, heads, ciphertext), heads, ciphertext)
    }

    /** Seal the space's current playlist at its current heads, as a v2 producer does. */
    private fun sealCurrent(f: Fixture): EncryptedSnapshot {
        val heads = Reconcile.heads(f.server.changes(f.spaceId))
        val state = f.session.playlist(f.spaceId)!!.snapshot().encodeToByteArray()
        val ct = crypto.encryptChange(f.key, SnapshotEnvelope.seal(f.spaceId, heads, state))
        return snapshot(f.spaceId, heads, ct)
    }

    @Test
    fun anEnvelopedSnapshotCarriesTheStateAfterCompaction() {
        val f = fixture()
        f.server.putSnapshot(sealCurrent(f))
        f.server.compact(f.spaceId)
        assertEquals(listOf("song-a", "song-b"), f.session.playlist(f.spaceId)!!.ids())
    }

    @Test
    fun aRelabelledSnapshotIsRefused() {
        val f = fixture()
        val genuine = sealCurrent(f)
        // Everything here needs no key: reuse the ciphertext, re-mint the id over a new frontier.
        for (frontier in listOf(listOf("blake3:cc"), genuine.frontier.take(0), genuine.frontier + "blake3:cc")) {
            f.server.putSnapshot(snapshot(f.spaceId, frontier, genuine.ciphertext))
            assertThrows("relabelled to $frontier", IllegalStateException::class.java) {
                f.session.playlist(f.spaceId)
            }
        }
    }

    @Test
    fun aCanonicalLegacySnapshotStillFolds() {
        val f = fixture()
        // Sealed before the envelope: the bare state, as `space rotate` wrote it.
        val state = f.session.playlist(f.spaceId)!!.snapshot().encodeToByteArray()
        f.server.putSnapshot(
            snapshot(f.spaceId, Reconcile.heads(f.server.changes(f.spaceId)), crypto.encryptChange(f.key, state)),
        )
        f.server.compact(f.spaceId)
        assertEquals(listOf("song-a", "song-b"), f.session.playlist(f.spaceId)!!.ids())
    }

    @Test
    fun aChangesCiphertextIsNotALegacySnapshot() {
        val f = fixture()
        val change = f.server.changes(f.spaceId).first()
        f.server.putSnapshot(snapshot(f.spaceId, listOf(change.changeId), change.ciphertext))
        assertThrows(IllegalStateException::class.java) { f.session.playlist(f.spaceId) }
    }

    @Test
    fun stringsEscapeLikeGo122SoLegacySnapshotsStayCanonical() {
        // Go 1.22+ `json.Marshal("a\bc\fd\x01")` is "a\bc\fd\u0001". A mismatch would make a
        // Go-written legacy snapshot fail the canonical check and lock the phone out of the space.
        assertEquals("\"a\\bc\\fd\\u0001\"", PsJson.goJsonString("a\bc\u000cd\u0001"))
    }
}
