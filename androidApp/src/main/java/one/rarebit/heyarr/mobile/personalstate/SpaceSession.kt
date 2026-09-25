package one.rarebit.heyarr.mobile.personalstate

import java.util.UUID

/**
 * The device-side orchestrator of encrypted personal state (§42, §46, ADR-0049):
 * it OPENS a space by finding the wrapped key sealed for THIS device and unwrapping
 * it, FOLDS the space (snapshot + changes, each decrypted and merged), and MINTS a
 * change (encrypt, compute heads, push, apply optimistically). The node only ever
 * sees ciphertext; nothing here hands it a key or plaintext (Invariant 6).
 *
 * It is stateless — every read and write re-opens the space — so a credential or
 * node swap needs no cache invalidation; the wrapped key on the peer is the source
 * of truth. All the CRDT parity lives in [Playlist]/[StarSet]/[PlayLog]/
 * [ReadingPositions]; this class is the encrypt/decrypt/HTTP glue around them.
 */
internal class SpaceSession(
    private val client: PersonalStateClient,
    private val device: DeviceEncKey,
    private val crypto: SpaceCrypto = VoidbindSpaceCrypto,
    /**
     * Extra recipient X25519 public keys a newly-created space is also wrapped for, so a
     * peer device can read it too (ADR-0022, ADR-0049): the OTHER authorised member
     * devices (their `denc` keys, from the membership this device already folds) and,
     * when it can be provisioned to the phone, the recovery key. Empty means "only this
     * device can read it" — the honest degraded default before enrolment completes.
     */
    private val additionalRecipients: () -> List<ByteArray> = { emptyList() },
    private val newSpaceId: () -> String = { UUID.randomUUID().toString() },
    private val newTag: () -> String = { UUID.randomUUID().toString() },
    private val newWriter: () -> String = { UUID.randomUUID().toString() },
) {
    fun listSpaces(): List<SpaceInfo> = client.listSpaces()

    /** True when this device holds a wrapped copy of the space's key it can unwrap. */
    fun canOpen(spaceId: String): Boolean = openKey(spaceId) != null

    private fun openKey(spaceId: String): ByteArray? {
        val mine = device.recipientId()
        val wrapped = client.wrappedKeys(spaceId).firstOrNull { it.recipient == mine }?.wrapped ?: return null
        return runCatching { crypto.unwrap(wrapped, device.seed()) }.getOrNull()
    }

    private class Folded<S>(val state: S, val changes: List<EncryptedChange>, val frontier: List<String>)

    private fun <S> load(
        spaceId: String,
        key: ByteArray,
        empty: () -> S,
        fromSnapshot: (String) -> S,
        apply: (S, String) -> Unit,
    ): Folded<S> {
        var state = empty()
        var frontier = emptyList<String>()
        val snap = client.snapshot(spaceId)
        if (snap != null && snap.validate()) {
            state = fromSnapshot(crypto.decryptChange(key, snap.ciphertext).decodeToString())
            frontier = snap.frontier
        }
        val changes = client.changes(spaceId)
        for (c in changes) {
            if (c.validate()) apply(state, crypto.decryptChange(key, c.ciphertext).decodeToString())
        }
        return Folded(state, changes, frontier)
    }

    /** Encrypt a minted change's plaintext, mint it at the current heads, and push it. */
    private fun post(spaceId: String, key: ByteArray, folded: Folded<*>, plaintext: String) {
        val heads = Reconcile.heads(folded.changes, folded.frontier)
        val ciphertext = crypto.encryptChange(key, plaintext.encodeToByteArray())
        client.putChange(spaceId, EncryptedChange.mint(spaceId, heads, ciphertext))
    }

    // --- reads --------------------------------------------------------------------

    fun playlist(spaceId: String): Playlist? {
        val key = openKey(spaceId) ?: return null
        return foldPlaylist(spaceId, key).state
    }

    fun starred(spaceId: String): StarSet? {
        val key = openKey(spaceId) ?: return null
        return foldStarred(spaceId, key).state
    }

    fun history(spaceId: String): PlayLog? {
        val key = openKey(spaceId) ?: return null
        return foldHistory(spaceId, key).state
    }

    fun readingPositions(spaceId: String): ReadingPositions? {
        val key = openKey(spaceId) ?: return null
        return foldReading(spaceId, key).state
    }

    // --- writes (optimistic: return the locally-applied state) --------------------

    fun addToPlaylist(spaceId: String, itemId: String): Playlist? = openKey(spaceId)?.let { key ->
        val f = foldPlaylist(spaceId, key)
        post(spaceId, key, f, f.state.add(itemId, newTag()).encode())
        f.state
    }

    fun removeFromPlaylist(spaceId: String, itemId: String): Playlist? = openKey(spaceId)?.let { key ->
        val f = foldPlaylist(spaceId, key)
        post(spaceId, key, f, f.state.remove(itemId).encode())
        f.state
    }

    fun star(spaceId: String, itemId: String): StarSet? = openKey(spaceId)?.let { key ->
        val f = foldStarred(spaceId, key)
        post(spaceId, key, f, f.state.star(itemId, newTag()).encode())
        f.state
    }

    fun unstar(spaceId: String, itemId: String): StarSet? = openKey(spaceId)?.let { key ->
        val f = foldStarred(spaceId, key)
        post(spaceId, key, f, f.state.unstar(itemId).encode())
        f.state
    }

    fun recordPlay(spaceId: String, itemId: String): PlayLog? = openKey(spaceId)?.let { key ->
        val f = foldHistory(spaceId, key)
        post(spaceId, key, f, f.state.record(itemId, newTag()).encode())
        f.state
    }

    fun setReadingPosition(spaceId: String, pubId: String, position: String): ReadingPositions? =
        openKey(spaceId)?.let { key ->
            val f = foldReading(spaceId, key)
            post(spaceId, key, f, f.state.set(pubId, position, newWriter()).encode())
            f.state
        }

    // --- create -------------------------------------------------------------------

    /**
     * Mint a new space of [kind], wrapping its key for THIS device and every recovery
     * recipient, and record it on the peer. Returns the client-minted space id.
     */
    fun createSpace(kind: String): String {
        val id = newSpaceId()
        val key = crypto.newSpaceKey()
        val recipients = ArrayList<WrappedKeyEntry>()
        recipients.add(WrappedKeyEntry(device.recipientId(), crypto.seal(key, device.publicKey())))
        for (pub in additionalRecipients()) {
            recipients.add(WrappedKeyEntry("x25519:" + Hex.encode(pub), crypto.seal(key, pub)))
        }
        client.createSpace(id, kind, recipients)
        return id
    }

    // --- folds --------------------------------------------------------------------

    private fun foldPlaylist(spaceId: String, key: ByteArray): Folded<Playlist> = load(spaceId, key, {
        Playlist()
    }, { Playlist.fromSnapshot(it) }) { s, pt -> s.apply(PlaylistChange.decode(pt)) }

    private fun foldStarred(spaceId: String, key: ByteArray): Folded<StarSet> =
        load(spaceId, key, { StarSet() }, { StarSet.fromSnapshot(it) }) { s, pt -> s.apply(StarChange.decode(pt)) }

    private fun foldHistory(spaceId: String, key: ByteArray): Folded<PlayLog> =
        load(spaceId, key, { PlayLog() }, { PlayLog.fromSnapshot(it) }) { s, pt -> s.apply(PlayChange.decode(pt)) }

    private fun foldReading(spaceId: String, key: ByteArray): Folded<ReadingPositions> = load(spaceId, key, {
        ReadingPositions()
    }, { ReadingPositions.fromSnapshot(it) }) { s, pt -> s.apply(PositionChange.decode(pt)) }
}
