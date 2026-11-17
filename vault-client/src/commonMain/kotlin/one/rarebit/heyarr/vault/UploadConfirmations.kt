package one.rarebit.heyarr.vault

/**
 * One blob's answer to `POST /api/v1/vault/blobs/confirm` (heyarr-core ADR-0096 amendment, #714).
 * [status] is the node's word, kept verbatim: `confirmed` (the pin was provisional and is now
 * permanent), `already_permanent`, `not_held` (this node holds no upload pin for the blob) or
 * `not_uploader` (a restricted caller named a blob another principal uploaded).
 */
data class BlobConfirmation(val blob: String, val status: String) {
    /** The node will keep the blob: there is nothing left for this writer to confirm. */
    val isPermanent: Boolean get() = status == CONFIRMED || status == ALREADY_PERMANENT

    companion object {
        const val CONFIRMED = "confirmed"
        const val ALREADY_PERMANENT = "already_permanent"
    }
}

/**
 * The confirm call, as a seam of its own rather than a defaulted member of [VaultSpace]: the
 * writers wrap their space (a retrying space, a fresh-credential space), and a default there would
 * let a wrapper drop the confirm silently — which, with provisional pins on, is a blob collected a
 * day after the drive started naming it. A writer has to be handed one of these on purpose.
 * [VaultSpaceClient] is the real one.
 */
fun interface BlobConfirmer {
    /**
     * Make this node's upload pins on [blobs] permanent; one answer per blob, in request order.
     * A node that predates the route has only permanent pins, so it answers with no results and
     * no error.
     */
    fun confirmBlobs(blobs: List<String>): List<BlobConfirmation>
}

/**
 * The writer's side of provisional upload pins (heyarr-core ADR-0096 amendment, #714): a vault
 * write uploads its blobs, pushes the change that names them, and only once the node ACCEPTED
 * that change confirms them. Confirming earlier brings back the leak the amendment closes (a
 * refused change would leave its blobs pinned for good); never confirming loses them to GC a day
 * later on a node with `placement.provisional_upload_pins` on.
 *
 * A failed confirm never fails the write: the change already landed and the drive names the
 * blobs, so reporting the write as failed would only make the caller write it again. The blobs
 * stay [pending] instead, and every later [afterAccepted] or [retryPending] (call it at the start of
 * a sync pass) sends them again, ahead of the new ones. Pending is held in memory, one instance
 * per writer: a process that exits with blobs pending has 24 hours, on a node with the flag on,
 * to come back and write again before they are collected. That is why the flag stays off until
 * the writers have shipped this.
 *
 * The confirming methods are synchronized, so writers on several threads may share one instance;
 * each call blocks on the network, like the rest of the client.
 */
class UploadConfirmations(private val confirmer: BlobConfirmer, private val log: (String) -> Unit = {}) {
    private val pendingBlobs = LinkedHashSet<String>()

    /** Blob ids accepted into a change but not yet confirmed (a failed confirm leaves them here). */
    val pending: Set<String>
        @kotlin.jvm.Synchronized get() = pendingBlobs.toSet()

    /**
     * Run [push] — the writer's push of one change, as it already makes it (through its own
     * retrying or fresh-credential space) — and, once the node accepted the change, confirm
     * [naming]: the blobs it names, its manifest and content. A refusal propagates from [push]
     * untouched and confirms nothing, so the re-seal-on-epoch-mismatch loop a writer runs around
     * this stays as it was, and the refused write's blobs expire on their own.
     *
     * `confirmations.afterAccepted(listOf(manifest, content)) { space.pushChange(id, heads, sealed, epoch) }`
     */
    fun <T> afterAccepted(naming: Collection<String>, push: () -> T): T {
        val result = push()
        accepted(naming)
        return result
    }

    /**
     * Confirm [blobs], named by a change the node has already accepted, together with anything
     * still pending from an earlier write. Never throws.
     */
    @kotlin.jvm.Synchronized
    fun accepted(blobs: Collection<String>) {
        // A malformed id is a bug in the writer, and the node would refuse the whole request for
        // it (400): kept pending, it would block every later confirm. Say so and leave it out.
        val (ids, malformed) = blobs.partition(::isVaultBlobId)
        malformed.forEach { log("vault: not confirming $it: not a blob id (blake3:<64 lowercase hex>)") }
        pendingBlobs.addAll(ids)
        if (pendingBlobs.isNotEmpty()) confirmPending()
    }

    /** Send whatever is still [pending] again. Never throws. */
    @kotlin.jvm.Synchronized
    fun retryPending() {
        if (pendingBlobs.isNotEmpty()) confirmPending()
    }

    private fun confirmPending() {
        val asked = pendingBlobs.toList()
        val results = askNode(asked) ?: return
        settle(asked, results)
    }

    /** The node's answers, or null (logged) when the confirm failed and everything stays pending. */
    private fun askNode(asked: List<String>): List<BlobConfirmation>? {
        // Catching everything is the point: the write has landed, and any failure here (transport,
        // 5xx, an unexpected body) is one to retry, not one to surface as a failed write.
        @Suppress("TooGenericExceptionCaught")
        return try {
            confirmer.confirmBlobs(asked)
        } catch (e: Exception) {
            log("vault: confirming ${asked.size} upload(s) failed, will retry on the next write: ${e.message}")
            null
        }
    }

    private fun settle(asked: List<String>, results: List<BlobConfirmation>) {
        // No results: the node predates the route (404) and its pins are all permanent already.
        if (results.isEmpty()) {
            pendingBlobs.removeAll(asked.toSet())
            return
        }
        val answered = results.associateBy { it.blob }
        for (blob in asked) {
            val r = answered[blob]
            when {
                // An answer that skips a blob is the node's fault; keep it and ask again.
                r == null -> log("vault: the node did not answer for blob $blob, will retry")

                r.isPermanent -> pendingBlobs.remove(blob)

                // not_held / not_uploader: asking again cannot change the answer, so say it once
                // and drop it — the blob is retained, or not, for reasons outside this write.
                else -> {
                    log("vault: the node did not confirm blob $blob (${r.status})")
                    pendingBlobs.remove(blob)
                }
            }
        }
    }
}

private val BLOB_ID = Regex("^blake3:[0-9a-f]{64}$")

/** The route's blob id pattern (`blake3:` and 64 lowercase hex characters). */
internal fun isVaultBlobId(s: String): Boolean = BLOB_ID.matches(s)
