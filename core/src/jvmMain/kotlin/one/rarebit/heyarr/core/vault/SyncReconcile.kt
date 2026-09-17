package one.rarebit.heyarr.core.vault

/**
 * The pure heart of the vault sync engine (W4.5): given what's on disk now, the remote
 * drive's resolved tree, and a per-device index of what THIS device last synced, decide
 * the actions to converge them. No I/O, no clock, no crypto — the daemon supplies the
 * scan and executes the plan, so this is unit-tested like the CRDT.
 *
 * The decided semantics (2026-09-17):
 *  - SAFE DELETES: a remote delete is emitted ONLY for a path the index proves this
 *    device already synced and is now gone — so a first run (empty index) or an
 *    unmounted/empty folder never wipes the remote.
 *  - CONFLICTS ON DISK: `Drive.resolved()` already invents the "conflicted copy" paths;
 *    they arrive here as ordinary remote entries and get downloaded like any other file.
 *  - HYBRID CHANGE DETECTION happens in the scan (mtime+size gate, BLAKE3 on mismatch);
 *    by the time a [LocalFile] reaches here it carries the plaintext hash.
 *
 * The index bridges the two hash spaces: a local file is identified by the BLAKE3 of its
 * PLAINTEXT, while the CRDT names a path by its CIPHERTEXT blob id (non-deterministic per
 * seal). So "did the local file change?" compares plaintext hashes, "did the remote
 * change?" compares blob ids, and the index remembers both for each synced path.
 */

/** A file as the scan sees it. [plaintextHash] is `blake3:<hex>` of the file bytes. */
data class LocalFile(val size: Long, val mtime: Long, val plaintextHash: String)

/** What this device recorded when it last synced [path]: the plaintext it had and the blob it uploaded/downloaded. */
data class SyncIndexEntry(val plaintextHash: String, val remoteBlob: String, val size: Long, val mtime: Long)

/** One convergence action. Paths are already [normalisePath]d. */
sealed interface SyncAction {
    /** Local file is new or changed → seal it, PUT the blob, push a PUT change. */
    data class UploadLocal(val path: String) : SyncAction

    /** Remote entry is new or changed → openAll the blob, write the file. [blob] is the ciphertext blob id. */
    data class DownloadRemote(val path: String, val blob: String) : SyncAction

    /** A synced file vanished locally and the remote is unchanged → push a DELETE change. */
    data class DeleteRemote(val path: String) : SyncAction

    /** The remote deleted a file this device still holds unchanged → remove it locally (to trash). */
    data class DeleteLocal(val path: String) : SyncAction
}

/**
 * Compute the convergence plan. [resolved] is `Drive.resolved()` keyed by path (winners +
 * relocated conflicted copies). All three maps are keyed by normalised path.
 */
fun reconcile(
    local: Map<String, LocalFile>,
    resolved: Map<String, DriveEntry>,
    index: Map<String, SyncIndexEntry>,
): List<SyncAction> {
    val actions = ArrayList<SyncAction>()
    val paths = LinkedHashSet<String>().apply { addAll(local.keys); addAll(resolved.keys); addAll(index.keys) }

    for (path in paths.sorted()) {
        val l = local[path]
        val r = resolved[path]
        val idx = index[path]

        val localChanged = l != null && (idx == null || l.plaintextHash != idx.plaintextHash)
        val remoteChanged = r != null && (idx == null || r.blob != idx.remoteBlob)

        when {
            // Present both sides, neither changed since last sync → nothing to do.
            l != null && r != null && !localChanged && !remoteChanged -> {}

            // Local edit (with or without a concurrent remote edit): push it. If the remote
            // also changed, the CRDT's max-register join turns this into a conflicted copy,
            // which a later cycle downloads to disk — no bytes lost.
            localChanged -> actions.add(SyncAction.UploadLocal(path))

            // Only the remote changed (or it's a brand-new remote entry, incl. a conflicted
            // copy) → materialise it locally.
            remoteChanged -> actions.add(SyncAction.DownloadRemote(path, r!!.blob))

            // A file this device synced is gone locally, remote unchanged → propagate the delete.
            idx != null && l == null && r != null -> actions.add(SyncAction.DeleteRemote(path))

            // The remote deleted a file this device still holds unchanged → drop it locally.
            idx != null && r == null && l != null && !localChanged -> actions.add(SyncAction.DeleteLocal(path))

            // A synced path gone on BOTH sides → just forget it (index cleanup, no action).
            idx != null && l == null && r == null -> {}

            else -> {}
        }
    }
    return actions
}
