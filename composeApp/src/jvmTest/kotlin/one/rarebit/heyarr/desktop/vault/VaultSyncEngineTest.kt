package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.core.vault.LocalFile
import one.rarebit.heyarr.core.vault.SyncIndexEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end sync: device A seals + uploads a file and pushes an encrypted CRDT change;
 * device B, sharing the same in-memory server + space key, pulls, decrypts, downloads and
 * materialises it. Real BLAKE3 / XChaCha (0.8.0) / frame codec / CRDT through the seams —
 * only the transport and disk are in-memory.
 */
class VaultSyncEngineTest {

    private class MemBlobStore : VaultBlobStore {
        val blobs = HashMap<String, ByteArray>()
        override fun putBlob(baseUrl: String, hash: String, bytes: ByteArray, credential: Credential): PutResult {
            blobs[hash] = bytes
            return PutResult.Stored(hash, bytes.size.toLong())
        }
        override fun fetchRange(baseUrl: String, hash: String, start: Long, end: Long, credential: Credential): ByteArray =
            blobs.getValue(hash).copyOfRange(start.toInt(), end.toInt())
        override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray = blobs.getValue(hash)
    }

    private class MemSpace : VaultSpace {
        val changes = ArrayList<EncryptedChange>()
        override fun pullChanges(spaceId: String): List<EncryptedChange> = changes.toList()
        override fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray): String {
            val id = Blake3.hashHex(ciphertext)
            changes.add(EncryptedChange(spaceId, id, parents, ciphertext))
            return id
        }
    }

    private class MemFolder(
        val files: MutableMap<String, ByteArray> = mutableMapOf(),
        val mtimes: MutableMap<String, Long> = mutableMapOf(),
    ) : VaultFolder {
        override fun scan(index: Map<String, SyncIndexEntry>): Map<String, LocalFile> =
            files.mapValues { (p, b) -> LocalFile(b.size.toLong(), mtimes[p] ?: 0, Blake3.hashHex(b)) }
        override fun read(path: String): ByteArray = files.getValue(path)
        override fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long) { files[path] = bytes; mtimes[path] = mtimeEpochSec }
        override fun trash(path: String) { files.remove(path); mtimes.remove(path) }
    }

    private val key = ByteArray(32) { it.toByte() }

    private fun engine(folder: VaultFolder, blobs: VaultBlobStore, space: VaultSpace) =
        VaultSyncEngine(folder, blobs, space, InMemorySyncIndexStore(), "http://x", Credential.Guest, "space-1", key)

    @Test
    fun twoDeviceRoundTrip() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val content = "hello vault — a longer body to be sure".encodeToByteArray()

        // Device A: has the file, syncs → seals + uploads + pushes a change.
        val folderA = MemFolder(mutableMapOf("docs/a.txt" to content), mutableMapOf("docs/a.txt" to 100L))
        val a = engine(folderA, blobs, space).syncOnce()
        assertEquals(1, a.uploaded)
        assertTrue(space.changes.isNotEmpty(), "a change was pushed")
        assertTrue(blobs.blobs.size >= 2, "content + manifest blobs uploaded")

        // Device B: empty folder, same server + key → pulls, decrypts, downloads, materialises.
        val folderB = MemFolder()
        val b = engine(folderB, blobs, space)
        val bs = b.syncOnce()
        assertEquals(1, bs.downloaded)
        assertTrue(content.contentEquals(folderB.files["docs/a.txt"]), "B materialised A's file byte-for-byte")

        // Idempotent: B synced again does nothing (its index now records the file).
        val bs2 = b.syncOnce()
        assertEquals(0, bs2.uploaded + bs2.downloaded + bs2.deletedLocal + bs2.deletedRemote)
    }

    @Test
    fun localEditReUploads() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val folder = MemFolder(mutableMapOf("a.txt" to "v1".encodeToByteArray()), mutableMapOf("a.txt" to 1L))
        val store = InMemorySyncIndexStore()
        val eng = VaultSyncEngine(folder, blobs, space, store, "http://x", Credential.Guest, "s", key)
        assertEquals(1, eng.syncOnce().uploaded)
        // Edit the file → next sync re-uploads (a new change).
        folder.files["a.txt"] = "v2-longer".encodeToByteArray(); folder.mtimes["a.txt"] = 2L
        val before = space.changes.size
        assertEquals(1, eng.syncOnce().uploaded)
        assertTrue(space.changes.size > before, "the edit pushed another change")
    }
}
