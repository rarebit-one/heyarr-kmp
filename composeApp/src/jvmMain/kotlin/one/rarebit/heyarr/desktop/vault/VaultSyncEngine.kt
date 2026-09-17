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
    /** Write [bytes] atomically and set the file's mtime, so the next scan sees it unchanged. */
    fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long)
    fun trash(path: String)
}

/** The real folder on disk: scan via [LocalScanner], atomic temp-then-rename writes, a local trash. */
class RealVaultFolder(private val root: Path) : VaultFolder {
    override fun scan(index: Map<String, SyncIndexEntry>): Map<String, LocalFile> = LocalScanner.scan(root, index)

    override fun read(path: String): ByteArray = Files.readAllBytes(root.resolve(path))

    override fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        val tmpDir = root.resolve(".sync-tmp").also { Files.createDirectories(it) }
        val tmp = Files.createTempFile(tmpDir, "dl", ".part")
        Files.write(tmp, bytes)
        Files.setLastModifiedTime(tmp, java.nio.file.attribute.FileTime.from(java.time.Instant.ofEpochSecond(mtimeEpochSec)))
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
 * One reconcile-and-apply pass of the vault sync engine (W4.5). It wires the pure pieces —
 * the local scan, the remote drive rebuilt from opaque changes, [reconcile]'s plan — and
 * executes it: seal+upload local edits, download+materialise remote ones, propagate the
 * safe deletes. All the crypto/CRDT/HTTP happen through injected seams, so a two-device
 * round trip is unit-tested with in-memory backends and a real space key.
 *
 * The daemon (WatchService + schedule) just calls [syncOnce] on its cadence; this class
 * holds no loop and no clock.
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
) {
    data class Stats(val uploaded: Int, val downloaded: Int, val deletedRemote: Int, val deletedLocal: Int)

    fun syncOnce(): Stats {
        val index = indexStore.load()
        val local = folder.scan(index)
        val drive = buildDrive()
        val resolved = drive.resolved().associateBy { it.path }
        val actions = reconcile(local, resolved, index)

        val newIndex = index.toMutableMap()
        var up = 0; var down = 0; var dr = 0; var dl = 0
        for (a in actions) {
            when (a) {
                is SyncAction.UploadLocal -> { upload(a.path, local.getValue(a.path), drive, newIndex); up++ }
                is SyncAction.DownloadRemote -> { download(a.path, a.blob, resolved.getValue(a.path), newIndex); down++ }
                is SyncAction.DeleteRemote -> { push(drive.delete(a.path)); newIndex.remove(a.path); dr++ }
                is SyncAction.DeleteLocal -> { folder.trash(a.path); newIndex.remove(a.path); dl++ }
            }
        }
        indexStore.save(newIndex)
        return Stats(up, down, dr, dl)
    }

    /** Rebuild the converged drive from the space's opaque changes (decrypt + fold). */
    private fun buildDrive(): Drive {
        val d = Drive()
        for (c in space.pullChanges(spaceId)) {
            val json = VoidbindEncryption.decryptChange(spaceKey, c.ciphertext).decodeToString()
            d.apply(Drive.parseChange(json))
        }
        return d
    }

    private fun upload(path: String, lf: LocalFile, drive: Drive, newIndex: MutableMap<String, SyncIndexEntry>) {
        val bytes = folder.read(path)
        val fileId = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val (content, manifest) = VaultFrame.seal(spaceKey, bytes, fileId)
        blobs.putBlob(baseUrl, manifest.content, content, credential)          // the ciphertext content blob
        val manifestBlob = VaultFrame.sealManifest(spaceKey, manifest)
        val manifestHash = Blake3.hashHex(manifestBlob)
        blobs.putBlob(baseUrl, manifestHash, manifestBlob, credential)         // the sealed manifest (the drive entry's blob)
        push(drive.put(path, manifestHash, lf.size, lf.mtime))
        newIndex[path] = SyncIndexEntry(lf.plaintextHash, manifestHash, lf.size, lf.mtime)
    }

    private fun download(path: String, manifestHash: String, entry: DriveEntry, newIndex: MutableMap<String, SyncIndexEntry>) {
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
