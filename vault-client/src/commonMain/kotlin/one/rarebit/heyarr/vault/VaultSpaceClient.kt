package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import kotlin.io.encoding.Base64

/**
 * The node refused a vault request with HTTP [status]. Space routes answer 401/403/404 alike for
 * "this credential may not see the space" (heyarr ADR-0104), which [VaultObjects] maps to
 * [VaultRefException.Forbidden]; anything else is a failure to retry or report.
 */
class VaultHttpException(val status: Int, message: String, val code: String? = null) : Exception(message) {
    /**
     * The node refused a change sealed at a key epoch that is not the space's current one
     * (heyarr-core #712): re-open the space and seal again under the current key.
     */
    val isChangeKeyEpochMismatch: Boolean get() = status == HTTP_CONFLICT && code == CHANGE_KEY_EPOCH_MISMATCH

    companion object {
        /** The problem `code` of that refusal. */
        const val CHANGE_KEY_EPOCH_MISMATCH = "change_key_epoch_mismatch"
        private const val HTTP_CONFLICT = 409
    }
}

/** One opaque encrypted CRDT change as it rides the wire (§72, ADR-0049). */
data class EncryptedChange(
    val spaceId: String,
    val changeId: String, // "blake3:<hex>" of the ciphertext (content-addressed)
    val parents: List<String>, // causal parents, sorted
    val ciphertext: ByteArray,
)

/** One opaque encrypted snapshot: a converged CRDT folded to a causal point. */
data class EncryptedSnapshot(
    val spaceId: String,
    val snapshotId: String,
    val frontier: List<String>,
    val ciphertext: ByteArray,
)

/**
 * A space key sealed to one recipient (X25519), as the peer stores it (§79). [epoch] is the key
 * epoch the copy seals (ADR-0103; 0 for a space never rotated, and for every copy sent at create).
 */
data class WrappedKey(val recipient: String, val wrapped: ByteArray, val epoch: Int = 0)

/**
 * A space's wrapped copies with its CURRENT key epoch (`GET /spaces/{id}/keys`, ADR-0103): a
 * device opens the space with its copy at [keyEpoch] and the key history back to epoch 0.
 */
data class SpaceKeyList(val keyEpoch: Int, val wrapped: List<WrappedKey>)

/**
 * One incremental pull: the changes that arrived after the cursor asked for, and the cursor to
 * ask with next time. [cursor] is the peer's OPAQUE arrival position — not a timestamp, not a
 * causal frontier, and not comparable across peers, so a device that repoints at a different
 * controller must restart from 0.
 */
data class ChangePage(val changes: List<EncryptedChange>, val cursor: Long)

/** The subset of the space sync pipe the sync engine drives — an interface so it fakes cleanly. */
interface VaultSpace {
    fun pullChanges(spaceId: String): List<EncryptedChange>

    /**
     * Pull only what arrived after [since] (0 = from the beginning). This is what makes a
     * steady-state sync free: a caught-up device transfers an empty list instead of the entire
     * change log, which for a large vault is tens of MB EVERY poll.
     */
    fun pullChangesSince(spaceId: String, since: Long): ChangePage

    /**
     * Push one encrypted change. With [keyEpoch] — the epoch whose key sealed it — the node stores
     * it only while that is still the space's current epoch (heyarr-core #712), and refuses it
     * otherwise with a [VaultHttpException] whose [VaultHttpException.isChangeKeyEpochMismatch] is
     * true. A node that predates the check ignores it.
     */
    fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray, keyEpoch: Int? = null): String

    /**
     * The space's CURRENT key epoch (`key_epoch` of `GET /spaces/{id}/keys`, ADR-0103), checked
     * before a pass seals anything, so a rotation that brought no new blob is still noticed. Null
     * = this pipe cannot tell (the default: a fake or a peer without epochs), and no check is made.
     */
    fun keyEpoch(spaceId: String): Int? = null
}

/**
 * The two reads that open a space ([SpaceOpen]): its wrapped copies with the current key epoch,
 * and its key history. The narrowest seam, so every client that opens spaces (the desktop's
 * [VaultSpaceClient], the android app's personal-state client, a test fake) opens them the
 * same way.
 */
interface SpaceKeySource {
    /** The space's wrapped copies with its current key epoch (`GET /spaces/{id}/keys`, ADR-0103). */
    fun spaceKeys(spaceId: String): SpaceKeyList

    /**
     * The space's key chain (`GET /spaces/{id}/key-history`): one opaque row per rotation, each
     * sealing the previous key under the next. Never asked for a space at epoch 0.
     */
    fun keyHistory(spaceId: String): List<KeyHistoryEntry>
}

/**
 * The space lifecycle the custody bootstrap drives (create + read the wrapped-key list) —
 * an interface so the desktop's `VaultCustody` fakes it in tests without a live node.
 */
interface VaultKeys : SpaceKeySource {
    /** The space keys sealed for each recipient — the caller picks its own to unwrap. */
    fun listKeys(spaceId: String): List<WrappedKey>

    /**
     * [listKeys] with the space's current key epoch (ADR-0103). The default is a peer that
     * predates key epochs: every space is at epoch 0.
     */
    override fun spaceKeys(spaceId: String): SpaceKeyList = SpaceKeyList(0, listKeys(spaceId))

    override fun keyHistory(spaceId: String): List<KeyHistoryEntry> = emptyList()

    /**
     * Mint a space of [kind] with its key wrapped for each recipient in [wrapped]; returns the
     * server-recorded id. The server enforces enrol-before-wrap (ADR-0049): every recipient must
     * be an enrolled device key or the identity's recovery key, or it answers 403.
     */
    fun createSpace(id: String, kind: String, wrapped: List<WrappedKey>): String
}

/**
 * The encrypted personal-state sync pipe for a vault space (`/api/v1/spaces/{id}/…` and
 * `/api/v1/vault/placements`). It moves only OPAQUE ciphertext — the server and peers
 * hold no key and merge nothing (Invariant 6). Follows the desktop client pattern
 * (`LibraryClient`): constructed with an [HttpTransport], base URL and [Credential];
 * blocking, call off the UI thread.
 *
 * A change/snapshot is content-addressed, and the id is NOT `blake3(ciphertext)`: it is the FRAMED
 * [PersonalStateId] digest over `(domain ‖ space ‖ parents/frontier ‖ ciphertext)`, byte-identical
 * to the Go peer's `computeID`. The peer re-derives it and 400s (`id_mismatch`) on any mismatch, so
 * this computes it the same way here rather than trusting the server's echo.
 */
class VaultSpaceClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) : VaultSpace,
    VaultKeys {
    override fun keyEpoch(spaceId: String): Int = spaceKeys(spaceId).keyEpoch

    /** Pull every opaque change the server holds for [spaceId]. */
    override fun pullChanges(spaceId: String): List<EncryptedChange> = pullChangesSince(spaceId, 0).changes

    /**
     * Pull the tail after [since]. An older peer that does not understand `?since` ignores it and
     * answers the whole log with no `cursor` field; that reads back as cursor 0, so this degrades
     * to the old full-pull behaviour instead of silently skipping changes.
     */
    override fun pullChangesSince(spaceId: String, since: Long): ChangePage {
        val url = changesUrl(baseUrl, spaceId) + if (since > 0) "?since=$since" else ""
        val resp = http.get(url, credential.asHeader())
        expectStatus(resp, 200) { "vault: GET changes failed: HTTP ${resp.status}" }
        val cursor = JsonScan.longField(resp.body, "cursor") ?: 0L
        val array = JsonScan.arrayOf(resp.body, listOf("changes")) ?: return ChangePage(emptyList(), cursor)
        return ChangePage(JsonScan.objectsOf(array, emptyList()).map { parseChange(it) }, cursor)
    }

    /**
     * Push one encrypted change; returns its content-addressed id. The id is the FRAMED
     * `PersonalStateId.changeId` (domain ‖ space ‖ parents ‖ ciphertext), not `blake3(ciphertext)` —
     * the peer re-derives it the same way and 400s (`ErrIDMismatch`) otherwise. Parents are sent in
     * the same canonical (sorted/deduped) form they were hashed in, so the peer's re-derivation
     * agrees.
     */
    override fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray, keyEpoch: Int?): String {
        val canonicalParents = PersonalStateId.canonical(parents)
        val changeId = PersonalStateId.changeId(spaceId, canonicalParents, ciphertext)
        val body = JsonWrite.obj(
            linkedMapOf(
                "space_id" to spaceId,
                "change_id" to changeId,
                "parents" to canonicalParents,
                "ciphertext" to b64(ciphertext),
            ),
        )
        // A query parameter, not a body field: a node that predates it ignores it rather than
        // refusing the body for an unknown field.
        val url = changesUrl(baseUrl, spaceId) + (keyEpoch?.let { "?key_epoch=$it" } ?: "")
        val resp = http.post(url, body, "application/json", credential.asHeader())
        if (resp.status != 201) {
            throw VaultHttpException(
                resp.status,
                "vault: POST change failed: HTTP ${resp.status}",
                code = runCatching { JsonScan.stringField(resp.body, "code") }.getOrNull(),
            )
        }
        val acked = JsonScan.stringField(resp.body, "change_id")
        require(acked == changeId) { "vault: server acked change id $acked, expected $changeId" }
        return changeId
    }

    /** The latest snapshot for [spaceId], or null if the server holds none (404). */
    fun getSnapshot(spaceId: String): EncryptedSnapshot? {
        val resp = http.get(snapshotUrl(baseUrl, spaceId), credential.asHeader())
        if (resp.status == 404) return null
        expectStatus(resp, 200) { "vault: GET snapshot failed: HTTP ${resp.status}" }
        return EncryptedSnapshot(
            spaceId = JsonScan.stringField(resp.body, "space_id") ?: spaceId,
            snapshotId = JsonScan.stringField(resp.body, "snapshot_id") ?: "",
            frontier = stringArray(JsonScan.arrayOf(resp.body, listOf("frontier"))),
            ciphertext = b64d(JsonScan.stringField(resp.body, "ciphertext") ?: ""),
        )
    }

    /** The space keys sealed for each recipient — the caller picks its own to unwrap. */
    override fun listKeys(spaceId: String): List<WrappedKey> = spaceKeys(spaceId).wrapped

    /**
     * The wrapped copies with the space's current `key_epoch` and each copy's `epoch` (ADR-0103).
     * A peer that predates epochs sends neither, which reads back as epoch 0 — exactly a space
     * never rotated.
     */
    override fun spaceKeys(spaceId: String): SpaceKeyList {
        val resp = http.get(keysUrl(baseUrl, spaceId), credential.asHeader())
        expectStatus(resp, 200) { "vault: GET keys failed: HTTP ${resp.status}" }
        val keyEpoch = JsonScan.intField(resp.body, "key_epoch") ?: 0
        val array = JsonScan.arrayOf(resp.body, listOf("wrapped_keys")) ?: return SpaceKeyList(keyEpoch, emptyList())
        return SpaceKeyList(
            keyEpoch,
            JsonScan.objectsOf(array, emptyList()).map {
                WrappedKey(
                    JsonScan.stringField(it, "recipient") ?: "",
                    b64d(JsonScan.stringField(it, "wrapped") ?: ""),
                    JsonScan.intField(it, "epoch") ?: 0,
                )
            },
        )
    }

    /** The space's opaque key chain, oldest epoch first as the peer serves it (ADR-0103). */
    override fun keyHistory(spaceId: String): List<KeyHistoryEntry> {
        val resp = http.get(keyHistoryUrl(baseUrl, spaceId), credential.asHeader())
        expectStatus(resp, 200) { "vault: GET key-history failed: HTTP ${resp.status}" }
        val array = JsonScan.arrayOf(resp.body, listOf("entries")) ?: return emptyList()
        return JsonScan.objectsOf(array, emptyList()).map {
            KeyHistoryEntry(
                JsonScan.intField(it, "epoch") ?: 0,
                b64d(JsonScan.stringField(it, "sealed_prev") ?: ""),
            )
        }
    }

    /**
     * Mint a space of [kind] (a `spaces.Kind`: personal/family/shared/research) with the given
     * wrapped-key copies, and return the id the server recorded. A vault is one person's drive,
     * so the desktop mints it `personal`. 403 here means this device is not yet enrolled (the
     * enrol-before-wrap gate) — the custody bootstrap surfaces that as "not ready", not a crash.
     */
    override fun createSpace(id: String, kind: String, wrapped: List<WrappedKey>): String {
        val body = JsonWrite.obj(
            linkedMapOf(
                "id" to id,
                "kind" to kind,
                "wrapped_keys" to wrapped.map {
                    linkedMapOf("recipient" to it.recipient, "wrapped" to b64(it.wrapped))
                },
            ),
        )
        val resp = http.post(spacesUrl(baseUrl), body, "application/json", credential.asHeader())
        expectStatus(resp, 201, 200) { "vault: POST /spaces failed: HTTP ${resp.status}" }
        return JsonScan.stringField(resp.body, "id") ?: id
    }

    /**
     * Pin a vault blob to a peer OTHER than the uploader, so it replicates there and GC
     * spares it (cross-site BR⇄Cove). Idempotent.
     */
    fun pinPlacement(blobHash: String, peerId: String) {
        val body = JsonWrite.obj(linkedMapOf("blob_hash" to blobHash, "peer_id" to peerId))
        val resp = http.post(placementsUrl(baseUrl), body, "application/json", credential.asHeader())
        expectStatus(resp, 200) { "vault: POST placement failed: HTTP ${resp.status}" }
    }

    /** Remove a placement pin (the blob may then be GC'd if it was the last reference). Idempotent. */
    fun unpinPlacement(blobHash: String, peerId: String) {
        val body = JsonWrite.obj(linkedMapOf("blob_hash" to blobHash, "peer_id" to peerId))
        val resp = http.delete(placementsUrl(baseUrl), body, "application/json", credential.asHeader())
        expectStatus(resp, 204, 200) { "vault: DELETE placement failed: HTTP ${resp.status}" }
    }

    /** A refusal keeps its HTTP status ([VaultHttpException]) so a caller can tell "not granted" from a failure. */
    private inline fun expectStatus(resp: HttpResponse, vararg ok: Int, message: () -> String) {
        if (resp.status !in ok) throw VaultHttpException(resp.status, message())
    }

    private fun parseChange(obj: String) = EncryptedChange(
        spaceId = JsonScan.stringField(obj, "space_id") ?: "",
        changeId = JsonScan.stringField(obj, "change_id") ?: "",
        parents = stringArray(JsonScan.arrayOf(obj, listOf("parents"))),
        ciphertext = b64d(JsonScan.stringField(obj, "ciphertext") ?: ""),
    )

    private fun b64(bytes: ByteArray): String = Base64.encode(bytes)
    private fun b64d(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Base64.decode(s)

    private fun stringArray(array: String?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < array.length && array[i] != '"') {
                    if (array[i] == '\\' && i + 1 < array.length) {
                        sb.append(array[i + 1])
                        i += 2
                    } else {
                        sb.append(array[i])
                        i++
                    }
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    companion object {
        private fun base(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1"
        private fun enc(s: String) = encodePathSegment(s)
        fun spacesUrl(baseUrl: String) = base(baseUrl) + "/spaces"
        fun changesUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/changes"
        fun snapshotUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/snapshot"
        fun keysUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/keys"
        fun keyHistoryUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/key-history"
        fun placementsUrl(baseUrl: String) = base(baseUrl) + "/vault/placements"
    }
}

/**
 * Percent-encode [s] for one URL path segment: every byte of its UTF-8 outside RFC 3986's
 * unreserved set. A space id is a UUID, which passes through unchanged (as it did under the
 * JDK's URLEncoder, which this replaces so the client is common code).
 */
internal fun encodePathSegment(s: String): String {
    val sb = StringBuilder(s.length)
    for (b in s.encodeToByteArray()) {
        val c = b.toUByte().toInt()
        val ch = c.toChar()
        if (ch in UNRESERVED) {
            sb.append(ch)
        } else {
            sb.append('%').append(HEX_UPPER[c / HEX_RADIX]).append(HEX_UPPER[c % HEX_RADIX])
        }
    }
    return sb.toString()
}

private const val HEX_UPPER = "0123456789ABCDEF"

internal const val HEX_RADIX = 16

// RFC 3986 §2.3 unreserved characters: the only bytes a path segment carries unescaped.
private val UNRESERVED: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()
