package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.vault.Drive
import one.rarebit.heyarr.core.vault.DriveChange
import one.rarebit.heyarr.core.vault.DriveEntry
import one.rarebit.heyarr.core.vault.LocalFile
import one.rarebit.heyarr.core.vault.SyncAction
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import one.rarebit.heyarr.core.vault.VaultFrame
import one.rarebit.heyarr.core.vault.encodeDriveChange
import one.rarebit.heyarr.core.vault.reconcile
import one.rarebit.voidbind.crypto.VoidbindEncryption
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
 */
class VaultSyncEngine(
    private val folder: VaultFolder,
    private val blobs: VaultBlobStore,
    private val space: VaultSpace,
    private val indexStore: SyncIndexStore,
    private val baseUrl: String,
    private val credential: Credential,
    private val spaceId: String,
    private val spaceKey: ByteArray,
) : VaultSync {
    data class Stats(val uploaded: Int, val downloaded: Int, val deletedRemote: Int, val deletedLocal: Int)

    /**
     * The folded remote drive, carried BETWEEN passes so a steady-state sync pulls nothing.
     * Null until the first successful fold. Held in memory only: a restarted daemon simply
     * starts from cursor 0 and pays one full pull, which is the old cost exactly once rather
     * than on every poll.
     */
    private var drive: Drive? = null

    /** The peer's opaque arrival position we have folded up to. 0 = nothing yet. */
    private var cursor: Long = 0

    override fun syncOnce(): Stats {
        val index = indexStore.load()
        val local = folder.scan(index)
        val drive = buildDrive()
        val resolved = drive.resolved().associateBy { it.path }
        val actions = reconcile(local, resolved, index)

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
        val d = drive ?: Drive()
        val page = space.pullChangesSince(spaceId, cursor)
        for (c in page.changes) {
            val json = VoidbindEncryption.decryptChange(spaceKey, c.ciphertext).decodeToString()
            d.apply(Drive.parseChange(json))
        }
        drive = d
        cursor = page.cursor
        return d
    }

    private fun upload(path: String, lf: LocalFile, drive: Drive, newIndex: MutableMap<String, SyncIndexEntry>) {
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
            blobs.putBlobFile(baseUrl, manifest.content, tmp, credential) // the ciphertext content blob (streamed off disk)
            val manifestBlob = VaultFrame.sealManifest(spaceKey, manifest)
            val manifestHash = Blake3.hashHex(manifestBlob)
            blobs.putBlob(baseUrl, manifestHash, manifestBlob, credential) // the sealed manifest (the drive entry's blob)
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
        val manifest = VaultFrame.openManifest(spaceKey, manifestBlob)
        val plaintext = VaultFrame.openAll(spaceKey, manifest, blobs.fetchFor(baseUrl, manifest.content, credential))
        folder.write(path, plaintext, entry.mtime)
        newIndex[path] = SyncIndexEntry(Blake3.hashHex(plaintext), manifestHash, plaintext.size.toLong(), entry.mtime)
    }

    private fun push(change: DriveChange) {
        val ciphertext = VoidbindEncryption.encryptChange(spaceKey, encodeDriveChange(change).encodeToByteArray())
        space.pushChange(spaceId, emptyList(), ciphertext) // TODO: causal parents = the applied frontier
    }
}
