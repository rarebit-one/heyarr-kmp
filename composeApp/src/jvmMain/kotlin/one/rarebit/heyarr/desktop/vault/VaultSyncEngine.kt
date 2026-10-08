package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.vault.Drive
import one.rarebit.heyarr.core.vault.DriveChange
import one.rarebit.heyarr.core.vault.DriveEntry
import one.rarebit.heyarr.core.vault.LocalFile
import one.rarebit.heyarr.core.vault.SpaceKeyring
import one.rarebit.heyarr.core.vault.SyncAction
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import one.rarebit.heyarr.core.vault.VaultFrame
import one.rarebit.heyarr.core.vault.encodeDriveChange
import one.rarebit.heyarr.core.vault.reconcile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/**
 * The designated vault folder as the engine touches it — a seam so [VaultSyncEngine] is
 * tested against an in-memory folder with no real disk.
 */
interface VaultFolder {
    fun scan(index: Map<String, SyncIndexEntry>): Map<String, LocalFile>
    fun read(path: String): ByteArray

    /**
     * Open [path] for STREAMING reads (the large-file seal path). Defaults to wrapping [read] so a
     * fake folder needn't implement it; the real folder streams straight off disk.
     */
    fun openRead(path: String): java.io.InputStream = java.io.ByteArrayInputStream(read(path))

    /**
     * A fresh temp file for a streaming seal's ciphertext, on the SAME filesystem as the vault (so
     * it is real disk — never a tmpfs `/tmp` that would put a multi-GB blob back in RAM). The caller
     * deletes it. Defaults to the system temp dir (fine for small test files); the real folder puts
     * it under the vault's ignored `.sync-tmp/`.
     */
    fun sealTemp(): Path = Files.createTempFile("heyarr-vault-seal", ".ct")

    /** Write [bytes] atomically and set the file's mtime, so the next scan sees it unchanged. */
    fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long)
    fun trash(path: String)
}

/** The real folder on disk: scan via [LocalScanner], atomic temp-then-rename writes, a local trash. */
class RealVaultFolder(private val root: Path) : VaultFolder {
    override fun scan(index: Map<String, SyncIndexEntry>): Map<String, LocalFile> = LocalScanner.scan(root, index)

    override fun read(path: String): ByteArray = Files.readAllBytes(root.resolve(path))

    override fun openRead(path: String): java.io.InputStream = Files.newInputStream(root.resolve(path))

    override fun sealTemp(): Path {
        val tmpDir = root.resolve(".sync-tmp").also { Files.createDirectories(it) }
        return Files.createTempFile(tmpDir, "seal", ".ct")
    }

    override fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        val tmpDir = root.resolve(".sync-tmp").also { Files.createDirectories(it) }
        val tmp = Files.createTempFile(tmpDir, "dl", ".part")
        Files.write(tmp, bytes)
        Files.setLastModifiedTime(
            tmp,
            java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(mtimeEpochSec)),
        )
        runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .onFailure { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
    }

    override fun trash(path: String) {
        val src = root.resolve(path)
        if (!Files.exists(src)) return
        val dest = root.resolve(".sync-trash").resolve(path)
        Files.createDirectories(dest.parent)
        Files.move(src, dest, StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * One reconcile-and-apply pass, as the daemon sees it — a seam so
 * [one.rarebit.heyarr.desktop.state.VaultSyncController] drives a fake pass in tests without a
 * real engine (no crypto, HTTP or disk). [VaultSyncEngine] is the production implementation.
 */
fun interface VaultSync {
    fun syncOnce(): VaultSyncEngine.Stats
}

/**
 * One reconcile-and-apply pass of the vault sync engine (W4.5). It wires the pure pieces —
 * the local scan, the remote drive rebuilt from opaque changes, [reconcile]'s plan — and
 * executes it: seal+upload local edits, download+materialise remote ones, propagate the
 * safe deletes. All the crypto/CRDT/HTTP happen through injected seams, so a two-device
 * round trip is unit-tested with in-memory backends and a real space key.
 *
 * The daemon ([one.rarebit.heyarr.desktop.state.VaultSyncController]) just calls [syncOnce] on
 * its cadence; this class holds no loop and no clock.
 *
 * The space's key can rotate (ADR-0103) and nothing is re-encrypted when it does, so the engine
 * holds the space's whole [keyring]: changes and manifests are opened with whichever key sealed
 * them (newest first), a file's frames with the key that opened its manifest, and everything this
 * device writes is sealed under the current key only. When no held key opens a blob, the space may
 * have rotated since the ring was fetched: [reopen] re-fetches the keys and history ONCE per pass
 * and the read is retried before the pass fails.
 *
 * A rotation that brings no newer blob would never trip that miss, and the engine would go on
 * sealing local writes under the pre-rotation key — readable by a recipient the rotation revoked.
 * So a pass that is about to write (an upload or a remote delete) first asks the peer for the
 * space's current key epoch, once, and re-opens before sealing anything if it moved; if it cannot
 * get onto the current key the pass fails rather than write under a stale one.
 */
@Suppress("LongParameterList") // each argument is one injected seam of the engine (was baselined)
class VaultSyncEngine(
    private val folder: VaultFolder,
    private val blobs: VaultBlobStore,
    private val space: VaultSpace,
    private val indexStore: SyncIndexStore,
    private val baseUrl: String,
    private val credential: Credential,
    private val spaceId: String,
    /** The space's keys, newest first; replaced when [reopen] re-fetches them. */
    private var keyring: SpaceKeyring,
    /** Where the folded drive + cursor survive a restart (#73). The default keeps nothing. */
    private val stateStore: DriveStateStore = NoDriveStateStore,
    /**
     * Re-open the space (re-fetch its key epoch, this device's copy and the key history) when no
     * held key opens a blob. Null result = could not re-open; the read then fails as it would have.
     * The default never re-opens.
     */
    private val reopen: () -> SpaceKeyring? = { null },
) : VaultSync {
    data class Stats(val uploaded: Int, val downloaded: Int, val deletedRemote: Int, val deletedLocal: Int)

    /**
     * The folded remote drive, carried BETWEEN passes so a steady-state sync pulls nothing.
     * Null until the first successful fold or a restored [stateStore] state. Persisted through
     * [stateStore] after every fold that took in new changes, so a restarted daemon resumes from
     * where it stopped instead of re-pulling the whole log (#73).
     */
    private var drive: Drive? = null

    /** The peer's opaque arrival position we have folded up to. 0 = nothing yet. */
    private var cursor: Long = 0

    /** Whether [stateStore] has been consulted since the drive was last discarded. */
    private var restored = false

    /** Whether this pass already re-fetched the keyring: at most once per pass, so a corrupt blob cannot loop. */
    private var reopened = false

    override fun syncOnce(): Stats {
        reopened = false
        val index = indexStore.load()
        val local = folder.scan(index)
        val drive = buildDrive()
        val resolved = drive.resolved().associateBy { it.path }
        val actions = reconcile(local, resolved, index)
        var completed = false
        try {
            return apply(actions, local, resolved, drive, index).also { completed = true }
        } finally {
            if (!completed) {
                // A pass that fails part-way can leave a local write in the drive that never
                // reached the server (a push that threw). Keeping that drive would make it look
                // remote from then on, and persisting it would carry that across restarts. So
                // drop it: the next pass restores the last saved fold, which holds only changes
                // the server has, or re-folds from 0 when nothing was saved.
                this.drive = null
                cursor = 0
                restored = false
            }
        }
    }

    private fun apply(
        actions: List<SyncAction>,
        local: Map<String, LocalFile>,
        resolved: Map<String, DriveEntry>,
        drive: Drive,
        index: Map<String, SyncIndexEntry>,
    ): Stats {
        if (actions.any { it is SyncAction.UploadLocal || it is SyncAction.DeleteRemote }) ensureCurrentKey()
        val newIndex = index.toMutableMap()
        var up = 0
        var down = 0
        var dr = 0
        var dl = 0
        for (a in actions) {
            when (a) {
                is SyncAction.UploadLocal -> {
                    upload(a.path, local.getValue(a.path), drive, newIndex)
                    up++
                }

                is SyncAction.DownloadRemote -> {
                    download(a.path, a.blob, resolved.getValue(a.path), newIndex)
                    down++
                }

                is SyncAction.DeleteRemote -> {
                    push(drive.delete(a.path))
                    newIndex.remove(a.path)
                    dr++
                }

                is SyncAction.DeleteLocal -> {
                    folder.trash(a.path)
                    newIndex.remove(a.path)
                    dl++
                }
            }
        }
        indexStore.save(newIndex)
        return Stats(up, down, dr, dl)
    }

    /**
     * The converged drive, advanced INCREMENTALLY: the drive folded so far plus whatever arrived
     * after [cursor].
     *
     * This used to rebuild from scratch every pass — re-downloading and re-decrypting the entire
     * change log each time, which on a large vault is tens of MB per poll forever, whether or not
     * anything changed. Folding the tail into the drive we already hold is EXACTLY equivalent
     * because [Drive.apply] is idempotent, commutative and associative (a semilattice): replaying
     * everything and applying the tail converge on the same state.
     *
     * The order below matters. The cursor advances only AFTER the page has been folded in, so a
     * decrypt or parse that throws mid-page leaves the cursor where it was and the next pass
     * re-fetches that page — re-applying changes already folded is a no-op, so a retry is safe.
     * On the very first pass [drive] is null, so a failure there discards the partial fold and the
     * next pass starts clean from 0.
     *
     * Local writes ([push]) mutate this same drive and are then shipped; the server hands them
     * back on a later pull and they fold in again idempotently, landing on the identical key.
     */
    private fun buildDrive(): Drive {
        if (drive == null && !restored) {
            restored = true
            stateStore.load(baseUrl, spaceId)?.let {
                drive = it.drive
                cursor = it.cursor
            }
        }
        val d = drive ?: Drive()
        val page = space.pullChangesSince(spaceId, cursor)
        for (c in page.changes) {
            val json = withKeyring { it.decryptChange(c.ciphertext) }.decodeToString()
            // An unknown op parses to null and is skipped, as Go's fold skips it (#111).
            Drive.parseChange(json)?.let { d.apply(it) }
        }
        drive = d
        cursor = page.cursor
        if (page.changes.isNotEmpty()) persist(d)
        return d
    }

    /**
     * Save the drive with the cursor it was just folded to. This runs straight after a fold,
     * before this pass writes anything locally, so the drive is exactly the log up to [cursor].
     * Earlier passes' local writes are in it too, but they were pushed before this pull, so they
     * are part of that log already.
     *
     * A failed save is not a failed sync. The file on disk is then an older but still consistent
     * pair, and the worst a restart pays is a longer tail.
     */
    private fun persist(d: Drive) {
        runCatching { stateStore.save(baseUrl, spaceId, DriveState(cursor, d)) }
    }

    private fun upload(path: String, lf: LocalFile, drive: Drive, newIndex: MutableMap<String, SyncIndexEntry>) {
        // One key for the content frames AND the manifest: a reader finds the frames' key by the
        // manifest it opens (ADR-0103), so the two must never straddle a re-fetched ring.
        val spaceKey = keyring.current
        val fileId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        // STREAM the seal to a temp ciphertext file, one frame in memory at a time — so a multi-GB
        // file neither OOMs the heap nor trips the JVM's ~2 GiB single-array cap. The random per-
        // frame nonces mean the ciphertext can't be re-derived, so we materialise it once (to disk)
        // and then stream that file to the content-addressed PUT.
        val tmp = folder.sealTemp()
        try {
            val manifest = folder.openRead(path).use { input ->
                Files.newOutputStream(tmp).buffered().use { out ->
                    VaultFrame.sealStreaming(
                        spaceKey,
                        fileId,
                        source = VaultFrame.PlaintextSource { buf, off, len -> input.read(buf, off, len) },
                        sink = VaultFrame.SealedSink { out.write(it) },
                    )
                }
            }
            // The ciphertext content blob, streamed off disk.
            blobs.putBlobFile(baseUrl, manifest.content, tmp, credential)
            val manifestBlob = VaultFrame.sealManifest(spaceKey, manifest)
            val manifestHash = Blake3.hashHex(manifestBlob)
            // The sealed manifest: the drive entry's blob.
            blobs.putBlob(baseUrl, manifestHash, manifestBlob, credential)
            push(drive.put(path, manifestHash, lf.size, lf.mtime))
            newIndex[path] = SyncIndexEntry(lf.plaintextHash, manifestHash, lf.size, lf.mtime)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun download(
        path: String,
        manifestHash: String,
        entry: DriveEntry,
        newIndex: MutableMap<String, SyncIndexEntry>,
    ) {
        val manifestBlob = blobs.fetchAll(baseUrl, manifestHash, credential)
        // The manifest picks the key (the file may predate a rotation); its frames are under it.
        val opened = withKeyring { VaultFrame.openManifest(it, manifestBlob) }
        val manifest = opened.manifest
        val plaintext = VaultFrame.openAll(opened.key, manifest, blobs.fetchFor(baseUrl, manifest.content, credential))
        folder.write(path, plaintext, entry.mtime)
        newIndex[path] = SyncIndexEntry(Blake3.hashHex(plaintext), manifestHash, plaintext.size.toLong(), entry.mtime)
    }

    /**
     * Before this pass seals anything: when the peer's current key epoch is not the ring's, the
     * space rotated without a newer blob reaching us. Re-open onto the new current key, or refuse
     * to write at all — never seal under a key a rotation retired.
     */
    private fun ensureCurrentKey() {
        val epoch = space.keyEpoch(spaceId) ?: return
        if (epoch == keyring.epoch) return
        val fresh = reopen()
        check(fresh != null && fresh.epoch >= epoch) {
            "vault: space $spaceId rotated to key epoch $epoch but this device could not open it " +
                "(holding epoch ${keyring.epoch}); refusing to write under a superseded key"
        }
        keyring = fresh
    }

    /**
     * Run [read] over the keyring; when no held key opens the blob, re-open the space once this
     * pass (it may have rotated since the ring was fetched) and retry with the fresh ring.
     */
    private fun <T> withKeyring(read: (SpaceKeyring) -> T): T = try {
        read(keyring)
    } catch (e: SpaceKeyring.NoKeyOpensException) {
        if (reopened) throw e
        reopened = true
        val fresh = runCatching { reopen() }.getOrNull() ?: throw e
        keyring = fresh
        read(fresh)
    }

    private fun push(change: DriveChange) {
        val ciphertext = keyring.encryptChange(encodeDriveChange(change).encodeToByteArray())
        space.pushChange(spaceId, emptyList(), ciphertext) // TODO: causal parents = the applied frontier
    }
}
