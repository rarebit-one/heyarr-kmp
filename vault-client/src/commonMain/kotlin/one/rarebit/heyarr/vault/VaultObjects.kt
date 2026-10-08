package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Who this device is to a space: its wrap recipient id (`x25519:<lowercase hex>`, Go
 * `encryption.FormatPublicKey`) and how it opens a copy wrapped for that id. The seed never
 * leaves the custody behind [unwrap] (a sealed device key, a keychain, a Go device store).
 */
class VaultRecipient(val id: String, val unwrap: (wrapped: ByteArray) -> ByteArray) {
    companion object {
        /** A software X25519 recipient: [publicKey] names it, [seed] is read only when a copy is unwrapped. */
        fun x25519(publicKey: ByteArray, seed: () -> ByteArray): VaultRecipient =
            VaultRecipient("x25519:" + publicKey.toHex()) { VoidbindEncryption.unwrap(it, seed()) }

        private fun ByteArray.toHex(): String = joinToString("") { it.toUByte().toString(HEX_RADIX).padStart(2, '0') }
    }
}

/** One sealed object read by its ref: the decrypted JSON envelope `{v:1, type, …}`. */
class VaultObject(val ref: VaultRef, val json: String) {
    /** The envelope's `v` (1 today), or null when absent — read as Go's put-ref reads it (last key wins). */
    val version: Int? get() = StrictJson.envelope(json)?.v?.toInt()

    /** The envelope's `type` (`question`, `answer`, `message`, `doc_index` …), or null when absent. */
    val type: String? get() = StrictJson.envelope(json)?.type

    // The plaintext stays out of logs and crash reports.
    override fun toString(): String = "VaultObject($ref, ${json.length} chars)"
}

/** Where [VaultObjects.put] landed an object — the same fields `heyarr vault put-ref` prints. */
data class PutRefResult(
    val ref: VaultRef,
    val path: String,
    val manifestBlob: String,
    val size: Long,
    val changeId: String,
)

/** A space's drive folded from its change log, with the changes (whose heads parent a new write). */
class LoadedDrive(val drive: Drive, val changes: List<EncryptedChange>) {
    /** The causal heads: every change no other change names as a parent (Go `protocol.Heads`). */
    fun heads(): List<String> {
        val referenced = HashSet<String>()
        for (c in changes) referenced.addAll(c.parents)
        return changes.map { it.changeId }.filter { it !in referenced }.distinct().sorted()
    }
}

/**
 * The trusted, ref-addressed vault client (heyarr ADR-0104; heyarr-core `vault get-ref` /
 * `put-ref`): open a space for [recipient], fold its drive, and read or write one sealed object at
 * `.jumpdrive/objects/<object uuid>.json` — the JSON envelope a private block's question, answer or
 * message is. Decryption happens HERE, on the device holding the key; [keys] and [space] move only
 * ciphertext, so a node or any service relaying a ref learns nothing but the ref.
 *
 * Opening follows [SpaceOpen] (current copy + key history, ADR-0103). A read tries every key on the
 * ring; a write seals under the current key only. When no held key opens a blob the space may have
 * rotated since it was opened, so the operation re-opens it ONCE and runs again — the same retry
 * the desktop sync engine and the android `SpaceSession` make.
 *
 * The drive is replayed from the full change log with no snapshot, exactly as heyarr-core's
 * `vault` commands do (`loadDrive`): the CRDT is order-independent, so a full replay converges.
 * A change whose content-addressed id does not match its bytes, or that no key opens, fails the
 * whole read (Go `DecodeAllChanges`): a hole would silently diverge the fold.
 *
 * Blocking, like every client here: call it off the UI thread.
 */
@Suppress(
    "LongParameterList", // each argument is one injected seam (node reads, blobs, custody, crypto, clock)
    "TooManyFunctions", // the get/put pipeline, one private step per outcome it classifies
)
class VaultObjects(
    private val keys: SpaceKeySource,
    private val space: VaultSpace,
    private val blobs: VaultBlobStore,
    private val baseUrl: String,
    private val credential: Credential,
    private val recipient: VaultRecipient,
    private val openPrev: (sealing: ByteArray, sealed: ByteArray) -> ByteArray = VoidbindEncryption::openSpaceKey,
    private val now: () -> Long = ::unixNow,
) {
    /** The usual wiring: one [VaultSpaceClient] serves both the key reads and the change log. */
    constructor(
        client: VaultSpaceClient,
        blobs: VaultBlobStore,
        baseUrl: String,
        credential: Credential,
        recipient: VaultRecipient,
    ) : this(client, client, blobs, baseUrl, credential, recipient)

    /**
     * The keyring of [spaceId] for this recipient. [VaultRefException.Forbidden] when the node
     * will not show this credential the space, [VaultRefException.Unwrap] when it holds no copy
     * for this recipient or the copy/history does not open. A transport failure is rethrown as is.
     */
    @Suppress("ThrowsCount") // one per outcome the CLI's exit codes distinguish
    fun openSpace(spaceId: String): SpaceKeyring {
        val fetchFailures = object : SpaceKeySource {
            override fun spaceKeys(spaceId: String) = fetching { keys.spaceKeys(spaceId) }
            override fun keyHistory(spaceId: String) = fetching { keys.keyHistory(spaceId) }
        }
        val ring = try {
            SpaceOpen.open(fetchFailures, spaceId, recipient.id, openPrev, recipient.unwrap)
        } catch (
            @Suppress("SwallowedException") e: FetchFailed, // only a marker: its cause is what is rethrown
        ) {
            throw classifyAccess(e.cause)
        } catch (e: VaultRefException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // any unwrap/unroll refusal is "cannot decrypt"
        ) {
            throw VaultRefException.Unwrap("opening space $spaceId: ${e.message}", e)
        }
        return ring ?: throw VaultRefException.Unwrap(
            "space $spaceId holds no copy of its key wrapped for ${recipient.id}",
        )
    }

    /** The space's drive folded from its whole change log under [ring]. */
    fun loadDrive(spaceId: String, ring: SpaceKeyring): LoadedDrive {
        val changes = try {
            space.pullChanges(spaceId)
        } catch (e: VaultHttpException) {
            throw classifyAccess(e)
        }
        val drive = Drive()
        for (c in changes) {
            check(c.changeId == PersonalStateId.changeId(c.spaceId, c.parents, c.ciphertext) && c.spaceId == spaceId) {
                "vault: refusing change ${c.changeId}: its id does not match its bytes"
            }
            val plaintext = ring.decryptChange(c.ciphertext).decodeToString()
            // Go's DecodeAllChanges fails the whole load on a change it cannot decode; the tolerant
            // scanner would apply one Go refuses or reads differently (a missing comma, a repeated
            // or case-varied key, a fractional integer), so the two clients would see different
            // drives. Refuse it the same way.
            Drive.goReadConflict(plaintext)?.let {
                throw VaultRefException.Integrity("vault: change ${c.changeId} of space $spaceId: $it")
            }
            // An unknown op parses to null and is skipped, as Go's fold skips it (#111).
            Drive.parseChange(plaintext)?.let { drive.apply(it) }
        }
        return LoadedDrive(drive, changes)
    }

    /** [get] for a ref in its wire form (`hv1:<space>/<object>`). */
    fun get(ref: String): VaultObject = get(VaultRef.parse(ref))

    /**
     * Read and decrypt the object [ref] names: open the space, fold the drive, find the object's
     * manifest at its path, open the manifest with whichever key sealed it and its frames with that
     * same key. The result must be a JSON object; its content is never put in an error.
     */
    @Suppress("ThrowsCount") // one per outcome the CLI's exit codes distinguish
    fun get(ref: VaultRef): VaultObject {
        val path = ref.path ?: throw VaultRefException.Malformed("$ref names a collection, not an object")
        val plaintext = withRing(ref.space) { ring ->
            val entry = loadDrive(ref.space, ring).drive.get(path)
                ?: throw VaultRefException.Absent("$ref: no object at that ref")
            if (entry.conflicted) throw VaultRefException.Absent("$ref: the object has conflicting versions")
            val manifestBlob = blob(ref) { integrity(ref) { blobs.fetchAll(baseUrl, entry.blob, credential) } }
            // Content addressing first: the manifest must be the one the drive entry names, or a
            // node could substitute another validly sealed manifest of this space. Then the manifest
            // picks the key (the object may predate a rotation); its frames are under it, and the
            // whole content blob is held to the id the manifest names.
            // Opening also validates the manifest's geometry (VaultFrame.validateManifest), before
            // any of it sizes a fetch or an allocation.
            val opened = integrity(ref) { VaultFrame.openManifestVerified(ring, manifestBlob, entry.blob) }
            // put-ref never seals more than MAX_OBJECT_BYTES; a manifest claiming more is not an
            // object this client reads into memory.
            if (opened.manifest.plaintextSize > MAX_OBJECT_BYTES) {
                throw VaultRefException.InvalidObject("$ref is larger than $MAX_OBJECT_BYTES bytes")
            }
            val content = blobs.fetchFor(baseUrl, opened.manifest.content, credential)
            blob(ref) { integrity(ref) { VaultFrame.openAllVerified(opened.key, opened.manifest, content) } }
        }
        val json = plaintext.decodeToString()
        if (StrictJson.envelope(json) == null) throw VaultRefException.InvalidObject("$ref is not a JSON object")
        return VaultObject(ref, json)
    }

    /**
     * Seal [json] into [space] (a space id or its `hv1:<space>` collection ref) under a fresh
     * random object id and record it in the space's drive — heyarr-core `vault put-ref`. The object
     * must be a versioned, typed envelope (`"v": 1` and a non-empty `"type"`) of at most
     * [MAX_OBJECT_BYTES]. The plaintext is sealed on this device; only ciphertext is uploaded.
     *
     * The space's key epoch is read before sealing AND again just before the drive change is
     * pushed; a rotation in between re-seals once under the new key, a second one (or one this
     * device cannot follow) is [VaultRefException.StaleEpoch]. The node does not yet make the push
     * conditional on the epoch, so a rotation committing within that last round trip still lands
     * this one write under the retired key.
     */
    fun put(space: String, json: String): PutRefResult {
        val spaceId = VaultRef.parseSpace(space)
        val bytes = json.encodeToByteArray()
        checkEnvelope(json, bytes.size)
        val ref = VaultRef.newObject(spaceId)
        val path = requireNotNull(ref.path)
        return withRing(spaceId) { opened -> sealAndPush(spaceId, ref, path, bytes, opened) }
    }

    /** [put] on an opened ring: seal, re-check the epoch, push — re-sealing once if the space rotated meanwhile. */
    private fun sealAndPush(
        spaceId: String,
        ref: VaultRef,
        path: String,
        bytes: ByteArray,
        opened: SpaceKeyring,
    ): PutRefResult {
        // The ring may already be stale: a rotation that landed after it was opened brings no
        // blob this write would fail to open, so nothing else would notice — and sealing under
        // the retired key would let a recipient that rotation revoked read the new object. Ask
        // for the current epoch now, right before sealing, and move onto it or refuse.
        var ring = writeRing(spaceId, opened, currentEpoch(spaceId))
        var attempt = 1
        while (true) {
            val sealed = sealAndRecord(spaceId, path, bytes, ring)
            // The node takes no expected epoch with a change (heyarr-core `putChange`), so the
            // epoch is checked AGAIN immediately before the push: a rotation that committed
            // while this write sealed, uploaded and folded the drive is caught here, and the
            // write re-seals under the new key once. The blobs already uploaded under the
            // retired key stay unreferenced — only a drive change names a blob id, and blob
            // ids are unguessable digests of fresh-nonce ciphertext — so nothing readable by
            // the revoked recipient is published. What remains is the one round trip between
            // this read and the push; closing it needs the node to refuse the push itself.
            val epoch = currentEpoch(spaceId)
            if (epoch == ring.epoch) {
                val changeId = try {
                    this.space.pushChange(spaceId, sealed.heads, sealed.change)
                } catch (e: VaultHttpException) {
                    throw classifyAccess(e)
                }
                return PutRefResult(ref, path, sealed.manifestId, sealed.size, changeId)
            }
            if (attempt++ >= MAX_SEAL_ATTEMPTS) {
                throw VaultRefException.StaleEpoch(
                    "space $spaceId rotated again (to key epoch $epoch) while the write was sealed; " +
                        "nothing was recorded",
                )
            }
            ring = writeRing(spaceId, ring, epoch)
        }
    }

    /** A write sealed and its blobs stored, with the drive change that would publish it — not yet pushed. */
    private class SealedWrite(val manifestId: String, val size: Long, val heads: List<String>, val change: ByteArray)

    /**
     * Seal [bytes] under [ring]'s current key, upload the content and manifest blobs, and encrypt
     * the drive change recording the manifest at [path], parented on the current heads.
     */
    private fun sealAndRecord(spaceId: String, path: String, bytes: ByteArray, ring: SpaceKeyring): SealedWrite {
        // One key for the content frames, the manifest AND the drive change: a reader finds the
        // frames' key by the manifest it opens (ADR-0103).
        val key = ring.current
        val (content, manifest) = VaultFrame.seal(key, bytes, newFileId())
        store(manifest.content, content)
        val sealedManifest = VaultFrame.sealManifest(key, manifest)
        val manifestId = VaultFrame.BLAKE3.hash(sealedManifest)
        store(manifestId, sealedManifest)
        val loaded = loadDrive(spaceId, ring)
        val change = loaded.drive.put(path, manifestId, manifest.plaintextSize, now())
        val ciphertext = ring.encryptChange(encodeDriveChange(change).encodeToByteArray())
        return SealedWrite(manifestId, manifest.plaintextSize, loaded.heads(), ciphertext)
    }

    /** The space's current key epoch as the node reports it now. */
    private fun currentEpoch(spaceId: String): Int = try {
        keys.spaceKeys(spaceId).keyEpoch
    } catch (e: VaultHttpException) {
        throw classifyAccess(e)
    }

    /** [SpaceOpen.currentForWrite], with "cannot follow the rotation" as [VaultRefException.StaleEpoch]. */
    private fun writeRing(spaceId: String, ring: SpaceKeyring, epoch: Int): SpaceKeyring = try {
        SpaceOpen.currentForWrite(epoch, ring, spaceId) {
            // Only "no key of this device opens it" is a rotation it cannot follow; an access
            // refusal or transport failure from openSpace propagates as itself.
            try {
                openSpace(spaceId)
            } catch (
                @Suppress("SwallowedException") e: VaultRefException.Unwrap, // reported as StaleEpoch below
            ) {
                null
            }
        }
    } catch (e: SpaceOpen.StaleKeyException) {
        throw VaultRefException.StaleEpoch(e.message ?: "the space rotated", e)
    }

    /** Run [op] on the space's ring; when no held key opens a blob it read, re-open once and run again. */
    private fun <T> withRing(spaceId: String, op: (SpaceKeyring) -> T): T {
        val ring = openSpace(spaceId)
        return try {
            op(ring)
        } catch (e: SpaceKeyring.NoKeyOpensException) {
            val fresh = openSpace(spaceId)
            if (fresh.epoch == ring.epoch) throw VaultRefException.Unwrap("no key on the space's ring opens it", e)
            op(fresh)
        }
    }

    private fun store(hash: String, bytes: ByteArray) {
        when (val r = blobs.putBlob(baseUrl, hash, bytes, credential)) {
            // The node re-derives the id; one that answers with another id did not store these bytes.
            is PutResult.Stored -> check(r.hash == hash) { "vault: node stored $hash as ${r.hash}" }

            // Access that went away after the space opened is the documented refusal, not a failure.
            is PutResult.Failed -> if (r.status == HTTP_UNAUTHORIZED || r.status == HTTP_FORBIDDEN) {
                throw VaultRefException.Forbidden(
                    r.status ?: HTTP_FORBIDDEN,
                    "vault: storing $hash refused: ${r.message}",
                )
            } else {
                error("vault: storing $hash: ${r.message}")
            }
        }
    }

    /**
     * A blob read after the space opened: access is settled, so a 404 is an absent object (Go
     * `classifyOpenedRead`).
     */
    private fun <T> blob(ref: VaultRef, read: () -> T): T = try {
        read()
    } catch (e: VaultHttpException) {
        if (e.status == HTTP_NOT_FOUND) throw VaultRefException.Absent("$ref: the node cannot serve its blobs", e)
        throw classifyAccess(e)
    }

    @Suppress("ThrowsCount") // one refusal per way an object is not an envelope
    private fun checkEnvelope(json: String, size: Int) {
        if (size > MAX_OBJECT_BYTES) {
            throw VaultRefException.InvalidObject("the object is larger than $MAX_OBJECT_BYTES bytes")
        }
        // Strict RFC 8259, decoded as heyarr-core's readObject decodes it (StrictJson).
        val env = StrictJson.envelope(json)
            ?: throw VaultRefException.InvalidObject("the object is not a JSON object")
        if (env.wrongType || env.v != 1L || env.type.isNullOrEmpty()) {
            throw VaultRefException.InvalidObject(
                "the object must be an envelope with \"v\": 1 and a non-empty \"type\"",
            )
        }
    }

    private class FetchFailed(override val cause: Throwable) : Exception(cause)

    private inline fun <T> fetching(read: () -> T): T = try {
        read()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception, // marks it a fetch failure, rethrown by openSpace
    ) {
        throw FetchFailed(e)
    }

    companion object {
        /**
         * The largest object [put] seals: a question, an answer or an index entry, never a document
         * (Go `maxVaultObject`).
         */
        const val MAX_OBJECT_BYTES = 8 shl 20

        /** A write re-seals at most once when the space rotates under it, then refuses. */
        private const val MAX_SEAL_ATTEMPTS = 2

        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404

        /** 401/403/404 from a space route → [VaultRefException.Forbidden] (Go `classifyAPI`); anything else as is. */
        private fun classifyAccess(e: Throwable): Throwable =
            if (e is VaultHttpException && e.status in setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND)) {
                VaultRefException.Forbidden(
                    e.status,
                    "the space is not visible to this credential (HTTP ${e.status})",
                    e,
                )
            } else {
                e
            }

        /**
         * A frame file id: 16 bytes from a CSPRNG (Kotlin's `Uuid.random` draws from one). Not a
         * secret — it binds each frame to its object against splicing — so 122 random bits suffice.
         */
        @OptIn(ExperimentalUuidApi::class)
        private fun newFileId(): ByteArray = Uuid.random().toByteArray()
    }
}

/** A blob whose bytes do not match its content address → [VaultRefException.Integrity]. */
private inline fun <T> integrity(ref: VaultRef, read: () -> T): T = try {
    read()
} catch (e: VaultFrame.IntegrityException) {
    throw VaultRefException.Integrity("$ref: ${e.message}", e)
} catch (e: VaultFrame.FrameException) {
    // A frame that fails its AEAD or its header binding is tampered or misplaced content.
    throw VaultRefException.Integrity("$ref: ${e.message}", e)
}

@OptIn(ExperimentalTime::class)
private fun unixNow(): Long = Clock.System.now().epochSeconds
