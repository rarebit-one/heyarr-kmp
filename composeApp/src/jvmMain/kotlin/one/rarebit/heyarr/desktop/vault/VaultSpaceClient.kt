package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import java.net.URLEncoder
import java.util.Base64

/** One opaque encrypted CRDT change as it rides the wire (§72, ADR-0049). */
data class EncryptedChange(
    val spaceId: String,
    val changeId: String,      // "blake3:<hex>" of the ciphertext (content-addressed)
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

/** A space key sealed to one recipient (X25519), as the peer stores it (§79). */
data class WrappedKey(val recipient: String, val wrapped: ByteArray)

/** The subset of the space sync pipe the sync engine drives — an interface so it fakes cleanly. */
interface VaultSpace {
    fun pullChanges(spaceId: String): List<EncryptedChange>
    fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray): String
}

/**
 * The encrypted personal-state sync pipe for a vault space (`/api/v1/spaces/{id}/…` and
 * `/api/v1/vault/placements`). It moves only OPAQUE ciphertext — the server and peers
 * hold no key and merge nothing (Invariant 6). Follows the desktop client pattern
 * (`LibraryClient`): constructed with an [HttpTransport], base URL and [Credential];
 * blocking, call off the UI thread.
 *
 * A change/snapshot is content-addressed: its id is `blake3:<hex>` of the ciphertext, and
 * the server re-verifies it (400 `id_mismatch` otherwise), so this computes the id here
 * with the vault's own [Blake3] rather than trusting the server's echo.
 */
class VaultSpaceClient(
    private val http: HttpTransport,
    private val baseUrl: String,
    private val credential: Credential,
) : VaultSpace {
    /** Pull every opaque change the server holds for [spaceId]. */
    override fun pullChanges(spaceId: String): List<EncryptedChange> {
        val resp = http.get(changesUrl(baseUrl, spaceId), credential.asHeader())
        require(resp.status == 200) { "vault: GET changes failed: HTTP ${resp.status}" }
        val array = JsonScan.arrayOf(resp.body, listOf("changes")) ?: return emptyList()
        return JsonScan.objectsOf(array, emptyList()).map { parseChange(it) }
    }

    /** Push one encrypted change; returns its content-addressed id. */
    override fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray): String {
        val changeId = Blake3.hashHex(ciphertext)
        val body = JsonWrite.obj(
            linkedMapOf(
                "space_id" to spaceId,
                "change_id" to changeId,
                "parents" to parents,
                "ciphertext" to b64(ciphertext),
            ),
        )
        val resp = http.post(changesUrl(baseUrl, spaceId), body, "application/json", credential.asHeader())
        require(resp.status == 201) { "vault: POST change failed: HTTP ${resp.status}" }
        val acked = JsonScan.stringField(resp.body, "change_id")
        require(acked == changeId) { "vault: server acked change id $acked, expected $changeId" }
        return changeId
    }

    /** The latest snapshot for [spaceId], or null if the server holds none (404). */
    fun getSnapshot(spaceId: String): EncryptedSnapshot? {
        val resp = http.get(snapshotUrl(baseUrl, spaceId), credential.asHeader())
        if (resp.status == 404) return null
        require(resp.status == 200) { "vault: GET snapshot failed: HTTP ${resp.status}" }
        return EncryptedSnapshot(
            spaceId = JsonScan.stringField(resp.body, "space_id") ?: spaceId,
            snapshotId = JsonScan.stringField(resp.body, "snapshot_id") ?: "",
            frontier = stringArray(JsonScan.arrayOf(resp.body, listOf("frontier"))),
            ciphertext = b64d(JsonScan.stringField(resp.body, "ciphertext") ?: ""),
        )
    }

    /** Push an encrypted snapshot; returns its content-addressed id. */
    fun pushSnapshot(spaceId: String, frontier: List<String>, ciphertext: ByteArray): String {
        val snapshotId = Blake3.hashHex(ciphertext)
        val body = JsonWrite.obj(
            linkedMapOf(
                "space_id" to spaceId,
                "snapshot_id" to snapshotId,
                "frontier" to frontier,
                "ciphertext" to b64(ciphertext),
            ),
        )
        val resp = http.post(snapshotsUrl(baseUrl, spaceId), body, "application/json", credential.asHeader())
        require(resp.status == 201) { "vault: POST snapshot failed: HTTP ${resp.status}" }
        val acked = JsonScan.stringField(resp.body, "snapshot_id")
        require(acked == snapshotId) { "vault: server acked snapshot id $acked, expected $snapshotId" }
        return snapshotId
    }

    /** The space keys sealed for each recipient — the caller picks its own to unwrap. */
    fun listKeys(spaceId: String): List<WrappedKey> {
        val resp = http.get(keysUrl(baseUrl, spaceId), credential.asHeader())
        require(resp.status == 200) { "vault: GET keys failed: HTTP ${resp.status}" }
        val array = JsonScan.arrayOf(resp.body, listOf("wrapped_keys")) ?: return emptyList()
        return JsonScan.objectsOf(array, emptyList()).map {
            WrappedKey(JsonScan.stringField(it, "recipient") ?: "", b64d(JsonScan.stringField(it, "wrapped") ?: ""))
        }
    }

    /**
     * Pin a vault blob to a peer OTHER than the uploader, so it replicates there and GC
     * spares it (cross-site BR⇄Cove). Idempotent.
     */
    fun pinPlacement(blobHash: String, peerId: String) {
        val body = JsonWrite.obj(linkedMapOf("blob_hash" to blobHash, "peer_id" to peerId))
        val resp = http.post(placementsUrl(baseUrl), body, "application/json", credential.asHeader())
        require(resp.status == 200) { "vault: POST placement failed: HTTP ${resp.status}" }
    }

    /** Remove a placement pin (the blob may then be GC'd if it was the last reference). Idempotent. */
    fun unpinPlacement(blobHash: String, peerId: String) {
        val body = JsonWrite.obj(linkedMapOf("blob_hash" to blobHash, "peer_id" to peerId))
        val resp = http.delete(placementsUrl(baseUrl), body, "application/json", credential.asHeader())
        require(resp.status == 204 || resp.status == 200) { "vault: DELETE placement failed: HTTP ${resp.status}" }
    }

    private fun parseChange(obj: String) = EncryptedChange(
        spaceId = JsonScan.stringField(obj, "space_id") ?: "",
        changeId = JsonScan.stringField(obj, "change_id") ?: "",
        parents = stringArray(JsonScan.arrayOf(obj, listOf("parents"))),
        ciphertext = b64d(JsonScan.stringField(obj, "ciphertext") ?: ""),
    )

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun b64d(s: String): ByteArray = if (s.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(s)

    private fun stringArray(array: String?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < array.length) {
            if (array[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < array.length && array[i] != '"') {
                    if (array[i] == '\\' && i + 1 < array.length) { sb.append(array[i + 1]); i += 2 } else { sb.append(array[i]); i++ }
                }
                out.add(sb.toString())
            }
            i++
        }
        return out
    }

    companion object {
        private fun base(baseUrl: String) = baseUrl.trimEnd('/') + "/api/v1"
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        fun changesUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/changes"
        fun snapshotUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/snapshot"
        fun snapshotsUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/snapshots"
        fun keysUrl(baseUrl: String, spaceId: String) = base(baseUrl) + "/spaces/" + enc(spaceId) + "/keys"
        fun placementsUrl(baseUrl: String) = base(baseUrl) + "/vault/placements"
    }
}
