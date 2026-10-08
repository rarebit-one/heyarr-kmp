package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.vault.SnapshotEnvelope
import one.rarebit.heyarr.core.vault.SpaceKeyring
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
 *
 * A space's key can rotate (ADR-0103) and nothing is re-encrypted when it does, so
 * opening a space yields a [SpaceKeyring]: this device's copy of the CURRENT key plus
 * every earlier key unrolled from the key history. Reads open each snapshot/change
 * with whichever key sealed it (newest first); writes use the current key only. A
 * rotation can land between fetching the keys and fetching the changes, so an
 * operation whose blob no held key opens re-opens the space once and runs again.
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
    fun canOpen(spaceId: String): Boolean = openState(spaceId) == OpenState.OPEN

    /** Whether this device can open a space, and if not, which kind of "not". */
    enum class OpenState {
        /** This device holds a readable copy and the key chain unrolls. */
        OPEN,

        /** The space holds no copy for this device (or is gone): this device is not a reader. */
        NO_COPY,

        /**
         * This device HAS a copy but the space would not open (it did not unwrap, the copy is
         * superseded, the history is inconsistent, or the node failed). The space exists; a
         * caller must surface this, never treat it as missing and mint a replacement — that
         * would orphan the user's state.
         */
        UNREADABLE,
    }

    fun openState(spaceId: String): OpenState = when (open(spaceId)) {
        is Opening.Open -> OpenState.OPEN
        Opening.NoCopy -> OpenState.NO_COPY
        is Opening.Unreadable -> OpenState.UNREADABLE
    }

    private sealed interface Opening {
        class Open(val ring: SpaceKeyring) : Opening
        object NoCopy : Opening
        class Unreadable(val cause: Throwable) : Opening
    }

    /**
     * Open the space, retrying the keys + history pair ONCE when it is inconsistent: they are two
     * reads, and a rotation committing between them is transient, not a broken space.
     */
    private fun open(spaceId: String): Opening {
        val first = openOnce(spaceId)
        return if (first is Opening.Unreadable) openOnce(spaceId) else first
    }

    /**
     * The space's keyring for this device (heyarr-core `spaceopen.Open`): its copy at the
     * current key epoch, unwrapped, and the key history unrolled to epoch 0.
     */
    private fun openOnce(spaceId: String): Opening = runCatching {
        val keys = client.spaceKeys(spaceId)
        val own = keys.wrapped.firstOrNull { it.recipient == device.recipientId() }
        if (own == null) {
            Opening.NoCopy
        } else {
            val current = crypto.unwrap(own.wrapped, device.seed())
            // A space at epoch 0 has no history and is not asked.
            val history = if (keys.keyEpoch == 0) emptyList() else client.keyHistory(spaceId)
            Opening.Open(SpaceKeyring.open(current, own.epoch, keys.keyEpoch, history, crypto::openSpaceKey))
        }
    }.getOrElse { Opening.Unreadable(it) }

    /** The keyring, or null when this device cannot read the space (either kind of "not"). */
    private fun openRing(spaceId: String): SpaceKeyring? = (open(spaceId) as? Opening.Open)?.ring

    /**
     * The key a write is sealed under: the CURRENT key as of now, not as of when the operation
     * opened the space. A rotation brings no blob this operation would fail to decrypt, so
     * without this check a write would go out under the retired key — readable by a recipient
     * the rotation revoked. One `GET /keys` per write; re-open if the epoch moved, and refuse to
     * write if this device cannot get onto the new key.
     */
    private fun writeKey(spaceId: String, ring: SpaceKeyring): ByteArray {
        val epoch = client.spaceKeys(spaceId).keyEpoch
        if (epoch == ring.epoch) return ring.current
        val fresh = openRing(spaceId)
        check(fresh != null && fresh.epoch >= epoch) {
            "space $spaceId rotated to key epoch $epoch but this device could not open it " +
                "(holding epoch ${ring.epoch}); refusing to write under a superseded key"
        }
        return fresh.current
    }

    /**
     * Run [op] with the space's keyring; when no held key opens a blob it read, the space may
     * have rotated after the keys were fetched, so re-open it ONCE and run [op] again.
     */
    private fun <T> withRing(spaceId: String, op: (SpaceKeyring) -> T): T? {
        val ring = openRing(spaceId) ?: return null
        return try {
            op(ring)
        } catch (e: SpaceKeyring.NoKeyOpensException) {
            op(openRing(spaceId) ?: throw e)
        }
    }

    /** Decrypt a snapshot or change under whichever key on [ring] sealed it. */
    private fun decrypt(ring: SpaceKeyring, blob: ByteArray): ByteArray =
        ring.open(blob, crypto::decryptChange).plaintext

    private class Folded<S>(val state: S, val changes: List<EncryptedChange>, val frontier: List<String>)

    /** How one CRDT kind is built empty, read from and written to its snapshot, and folded. */
    private class Kind<S>(
        val empty: () -> S,
        val fromSnapshot: (String) -> S,
        val snapshotOf: (S) -> String,
        val apply: (S, String) -> Unit,
    )

    private fun <S> load(spaceId: String, key: SpaceKeyring, kind: Kind<S>): Folded<S> {
        var state = kind.empty()
        var frontier = emptyList<String>()
        val snap = client.snapshot(spaceId)
        if (snap != null && snap.validate()) {
            state = openSnapshot(snap, decrypt(key, snap.ciphertext), kind)
            frontier = snap.frontier
        }
        val changes = client.changes(spaceId)
        for (c in changes) {
            if (c.validate()) kind.apply(state, decrypt(key, c.ciphertext).decodeToString())
        }
        return Folded(state, changes, frontier)
    }

    /**
     * The state inside a decrypted snapshot, after checking what it was sealed with (heyarr-core#681).
     * The frontier and space ride outside the ciphertext, and the snapshot id is a public digest, so
     * a `write` token with no space key could relabel a valid snapshot; the envelope inside the
     * ciphertext is what proves the frontier. A relabelled snapshot fails the fold rather than being
     * folded under a causal point it was never taken at — the same fail-closed stance an undecryptable
     * change already gets here.
     *
     * A snapshot sealed before the envelope is still read, since a key rotation can leave one as the
     * only copy of the state. It must then be the CANONICAL snapshot of this kind (it re-serialises to
     * its own bytes), so another record's ciphertext — a change — cannot pass as an empty snapshot and
     * hide what compaction removed from the log.
     */
    private fun <S> openSnapshot(snap: EncryptedSnapshot, plaintext: ByteArray, kind: Kind<S>): S =
        when (val opened = SnapshotEnvelope.open(snap.spaceId, snap.frontier, plaintext)) {
            is SnapshotEnvelope.Opened.Authenticated -> kind.fromSnapshot(opened.state.decodeToString())

            is SnapshotEnvelope.Opened.Legacy -> {
                val json = opened.state.decodeToString()
                val state = kind.fromSnapshot(json)
                check(kind.snapshotOf(state) == json) {
                    "refusing snapshot ${snap.snapshotId}: not a canonical legacy snapshot"
                }
                state
            }

            is SnapshotEnvelope.Opened.Refused -> error("refusing snapshot ${snap.snapshotId}: ${opened.reason}")
        }

    /** Encrypt a minted change's plaintext under the CURRENT key, mint it at the current heads, and push it. */
    private fun post(spaceId: String, key: SpaceKeyring, folded: Folded<*>, plaintext: String) {
        val heads = Reconcile.heads(folded.changes, folded.frontier)
        val ciphertext = crypto.encryptChange(writeKey(spaceId, key), plaintext.encodeToByteArray())
        client.putChange(spaceId, EncryptedChange.mint(spaceId, heads, ciphertext))
    }

    // --- reads --------------------------------------------------------------------

    fun playlist(spaceId: String): Playlist? = withRing(spaceId) { foldPlaylist(spaceId, it).state }

    fun starred(spaceId: String): StarSet? = withRing(spaceId) { foldStarred(spaceId, it).state }

    fun history(spaceId: String): PlayLog? = withRing(spaceId) { foldHistory(spaceId, it).state }

    fun readingPositions(spaceId: String): ReadingPositions? = withRing(spaceId) { foldReading(spaceId, it).state }

    // --- writes (optimistic: return the locally-applied state) --------------------

    fun addToPlaylist(spaceId: String, itemId: String): Playlist? = withRing(spaceId) { key ->
        val f = foldPlaylist(spaceId, key)
        post(spaceId, key, f, f.state.add(itemId, newTag()).encode())
        f.state
    }

    fun removeFromPlaylist(spaceId: String, itemId: String): Playlist? = withRing(spaceId) { key ->
        val f = foldPlaylist(spaceId, key)
        post(spaceId, key, f, f.state.remove(itemId).encode())
        f.state
    }

    fun star(spaceId: String, itemId: String): StarSet? = withRing(spaceId) { key ->
        val f = foldStarred(spaceId, key)
        post(spaceId, key, f, f.state.star(itemId, newTag()).encode())
        f.state
    }

    fun unstar(spaceId: String, itemId: String): StarSet? = withRing(spaceId) { key ->
        val f = foldStarred(spaceId, key)
        post(spaceId, key, f, f.state.unstar(itemId).encode())
        f.state
    }

    fun recordPlay(spaceId: String, itemId: String): PlayLog? = withRing(spaceId) { key ->
        val f = foldHistory(spaceId, key)
        post(spaceId, key, f, f.state.record(itemId, newTag()).encode())
        f.state
    }

    fun setReadingPosition(spaceId: String, pubId: String, position: String): ReadingPositions? =
        withRing(spaceId) { key ->
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

    private val playlistKind = Kind<Playlist>(
        empty = { Playlist() },
        fromSnapshot = { Playlist.fromSnapshot(it) },
        snapshotOf = { it.snapshot() },
        // An unknown op decodes to null and is ignored, never coerced (heyarr-kmp#111).
        apply = { s, pt -> PlaylistChange.decode(pt)?.let(s::apply) },
    )

    private val starredKind = Kind<StarSet>(
        empty = { StarSet() },
        fromSnapshot = { StarSet.fromSnapshot(it) },
        snapshotOf = { it.snapshot() },
        apply = { s, pt -> StarChange.decode(pt)?.let(s::apply) },
    )

    private val historyKind = Kind<PlayLog>(
        empty = { PlayLog() },
        fromSnapshot = { PlayLog.fromSnapshot(it) },
        snapshotOf = { it.snapshot() },
        apply = { s, pt -> s.apply(PlayChange.decode(pt)) },
    )

    private val readingKind = Kind<ReadingPositions>(
        empty = { ReadingPositions() },
        fromSnapshot = { ReadingPositions.fromSnapshot(it) },
        snapshotOf = { it.snapshot() },
        apply = { s, pt -> s.apply(PositionChange.decode(pt)) },
    )

    private fun foldPlaylist(spaceId: String, key: SpaceKeyring): Folded<Playlist> = load(spaceId, key, playlistKind)

    private fun foldStarred(spaceId: String, key: SpaceKeyring): Folded<StarSet> = load(spaceId, key, starredKind)

    private fun foldHistory(spaceId: String, key: SpaceKeyring): Folded<PlayLog> = load(spaceId, key, historyKind)

    private fun foldReading(spaceId: String, key: SpaceKeyring): Folded<ReadingPositions> =
        load(spaceId, key, readingKind)
}
