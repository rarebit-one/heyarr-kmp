package one.rarebit.heyarr.desktop.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.crypto.Blake3
import one.rarebit.heyarr.vault.ChangePage
import one.rarebit.heyarr.vault.EncryptedChange
import one.rarebit.heyarr.vault.KeyHistoryEntry
import one.rarebit.heyarr.vault.LocalFile
import one.rarebit.heyarr.vault.PutResult
import one.rarebit.heyarr.vault.SpaceKeyring
import one.rarebit.heyarr.vault.SyncIndexEntry
import one.rarebit.heyarr.vault.VaultBlobStore
import one.rarebit.heyarr.vault.VaultSpace
import one.rarebit.voidwhichbinds.crypto.VoidbindEncryption
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        override fun fetchRange(
            baseUrl: String,
            hash: String,
            start: Long,
            end: Long,
            credential: Credential,
        ): ByteArray = blobs.getValue(hash).copyOfRange(start.toInt(), end.toInt())
        override fun fetchAll(baseUrl: String, hash: String, credential: Credential): ByteArray = blobs.getValue(hash)
    }

    private class MemSpace : VaultSpace {
        val changes = ArrayList<EncryptedChange>()
        override fun pullChanges(spaceId: String): List<EncryptedChange> = changes.toList()

        /**
         * Counts what actually went over the wire, so a test can assert that a caught-up pass
         * transfers NOTHING — the whole point of the cursor.
         */
        var served = 0
            private set

        override fun pullChangesSince(spaceId: String, since: Long): ChangePage {
            val tail = changes.drop(since.toInt())
            served += tail.size
            return ChangePage(tail, changes.size.toLong())
        }

        /** The key epoch the fake peer reports (null = a peer that cannot tell). */
        var epoch: Int? = null
        var epochChecks = 0
            private set

        override fun keyEpoch(spaceId: String): Int? {
            epochChecks++
            return epoch
        }

        /** When set, the next push throws — a network that dropped mid-pass. */
        var failNextPush = false

        /** The `key_epoch` each push named (heyarr-core #712). */
        val pushedEpochs = ArrayList<Int?>()

        override fun pushChange(spaceId: String, parents: List<String>, ciphertext: ByteArray, keyEpoch: Int?): String {
            pushedEpochs.add(keyEpoch)
            if (failNextPush) {
                failNextPush = false
                error("push failed")
            }
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
        override fun write(path: String, bytes: ByteArray, mtimeEpochSec: Long) {
            files[path] = bytes
            mtimes[path] = mtimeEpochSec
        }
        override fun trash(path: String) {
            files.remove(path)
            mtimes.remove(path)
        }
    }

    private val key = SpaceKeyring.single(ByteArray(32) { it.toByte() })

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

    // --- key rotation (ADR-0103) ----------------------------------------------------

    /** A space rotated once: k0 → k1, with the history row sealing k0 under k1. */
    private val k0 = ByteArray(32) { (it + 1).toByte() }
    private val k1 = ByteArray(32) { (it + 101).toByte() }
    private val rotated =
        SpaceKeyring.unroll(k1, 1, listOf(KeyHistoryEntry(1, VoidbindEncryption.sealSpaceKey(k1, k0))))

    private fun engineWith(
        folder: VaultFolder,
        blobs: VaultBlobStore,
        space: VaultSpace,
        ring: SpaceKeyring,
        reopen: () -> SpaceKeyring? = { null },
    ) = VaultSyncEngine(
        folder,
        blobs,
        space,
        InMemorySyncIndexStore(),
        "http://x",
        Credential.Guest,
        "space-1",
        ring,
        reopen = reopen,
    )

    @Test
    fun aFileWrittenBeforeARotationStaysReadableAndNewWritesUseTheCurrentKey() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val old = "written at epoch 0".encodeToByteArray()
        // Device A wrote at epoch 0: change, manifest and frames all under k0.
        val folderA = MemFolder(mutableMapOf("old.txt" to old), mutableMapOf("old.txt" to 1L))
        engineWith(folderA, blobs, space, SpaceKeyring.single(k0)).syncOnce()

        // Device B opens after the rotation, holding the unrolled ring [k1, k0], and adds a file.
        val fresh = "written at epoch 1".encodeToByteArray()
        val folderB = MemFolder(mutableMapOf("new.txt" to fresh), mutableMapOf("new.txt" to 2L))
        val b = engineWith(folderB, blobs, space, rotated).syncOnce()
        assertEquals(1, b.downloaded)
        assertTrue(old.contentEquals(folderB.files["old.txt"]), "the epoch-0 file opened through the ring")

        // B's own change is sealed under k1 only: k1 opens it, k0 does not.
        val bChange = space.changes.last().ciphertext
        assertTrue(VoidbindEncryption.decryptChange(k1, bChange).isNotEmpty())
        assertFailsWith<Exception> { VoidbindEncryption.decryptChange(k0, bChange) }
    }

    @Test
    fun aRingStaleAfterARotationIsReFetchedOnceAndTheReadRetried() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val body = "written after the rotation".encodeToByteArray()
        // A writer already on epoch 1.
        val folderA = MemFolder(mutableMapOf("a.txt" to body), mutableMapOf("a.txt" to 1L))
        engineWith(folderA, blobs, space, rotated).syncOnce()

        // B still holds the epoch-0 ring it opened before the rotation landed.
        var reopens = 0
        val folderB = MemFolder()
        val b = engineWith(folderB, blobs, space, SpaceKeyring.single(k0)) {
            reopens++
            rotated
        }
        assertEquals(1, b.syncOnce().downloaded)
        assertEquals(1, reopens, "one re-fetch, then the read succeeded")
        assertTrue(body.contentEquals(folderB.files["a.txt"]))
        // The fresh ring is kept: the next pass needs no re-fetch.
        folderA.files["a2.txt"] = "more".encodeToByteArray()
        engineWith(folderA, blobs, space, rotated).syncOnce()
        b.syncOnce()
        assertEquals(1, reopens)
    }

    @Test
    fun aRotationWithNoInboundBlobStillMovesLocalWritesToTheNewKey() {
        val blobs = MemBlobStore()
        val space = MemSpace().apply { epoch = 0 }
        val folder = MemFolder(mutableMapOf("a.txt" to "v1".encodeToByteArray()), mutableMapOf("a.txt" to 1L))
        var reopens = 0
        val eng = engineWith(folder, blobs, space, SpaceKeyring.single(k0)) {
            reopens++
            rotated
        }
        eng.syncOnce()
        assertEquals(0, reopens, "epoch unchanged: no re-open")

        // The space rotates to k1; nothing new arrives, so no decrypt ever misses.
        space.epoch = 1
        folder.files["a.txt"] = "v2 after the rotation".encodeToByteArray()
        folder.mtimes["a.txt"] = 2L
        val checks = space.epochChecks
        assertEquals(1, eng.syncOnce().uploaded)
        assertEquals(1, reopens)
        assertEquals(1, space.epochChecks - checks, "one epoch check per writing pass, not per blob")
        assertEquals(listOf<Int?>(0, 1), space.pushedEpochs, "each push names the epoch that sealed it")

        // The new drive change and the new manifest open under k1 only.
        val change = space.changes.last().ciphertext
        assertFailsWith<Exception> { VoidbindEncryption.decryptChange(k0, change) }
        // The drive change names the manifest blob it points at.
        val json = VoidbindEncryption.decryptChange(k1, change).decodeToString()
        val manifestHash = blobs.blobs.keys.single { it in json }
        val manifest = blobs.blobs.getValue(manifestHash)
        assertFailsWith<Exception> { VoidbindEncryption.decryptChange(k0, manifest) }
        assertTrue(VoidbindEncryption.decryptChange(k1, manifest).isNotEmpty())
    }

    @Test
    fun aPassThatCannotReachTheCurrentKeyWritesNothing() {
        val blobs = MemBlobStore()
        val space = MemSpace().apply { epoch = 1 }
        val folder = MemFolder(mutableMapOf("a.txt" to "x".encodeToByteArray()), mutableMapOf("a.txt" to 1L))
        val eng = engineWith(folder, blobs, space, SpaceKeyring.single(k0)) { null }
        assertFailsWith<IllegalStateException> { eng.syncOnce() }
        assertTrue(space.changes.isEmpty(), "nothing pushed under the superseded key")
        assertTrue(blobs.blobs.isEmpty(), "nothing uploaded under the superseded key")
    }

    @Test
    fun aBlobNoKeyOpensFailsThePassAfterOneReFetch() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val folderA = MemFolder(mutableMapOf("a.txt" to "x".encodeToByteArray()), mutableMapOf("a.txt" to 1L))
        engineWith(folderA, blobs, space, rotated).syncOnce()

        var reopens = 0
        val b = engineWith(MemFolder(), blobs, space, SpaceKeyring.single(k0)) {
            reopens++
            SpaceKeyring.single(k0) // the re-fetch still yields no key that opens it
        }
        assertFailsWith<SpaceKeyring.NoKeyOpensException> { b.syncOnce() }
        assertEquals(1, reopens, "at most one re-fetch per pass")
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
        folder.files["a.txt"] = "v2-longer".encodeToByteArray()
        folder.mtimes["a.txt"] = 2L
        val before = space.changes.size
        assertEquals(1, eng.syncOnce().uploaded)
        assertTrue(space.changes.size > before, "the edit pushed another change")
    }

    /**
     * The bandwidth contract. A caught-up engine must pull NOTHING on a quiet pass. Before the
     * cursor existed this re-fetched the entire change log every single pass, which on a real
     * vault meant tens of MB per poll around the clock.
     *
     * SABOTAGE (the reviewer's break): drop the `cursor` field in buildDrive and pass 0 — the
     * quiet passes then re-serve the whole log and `served` climbs.
     */
    @Test
    fun aCaughtUpPassTransfersNothing() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val folder = MemFolder(
            mutableMapOf("a.txt" to "one".encodeToByteArray(), "b.txt" to "two".encodeToByteArray()),
            mutableMapOf("a.txt" to 1L, "b.txt" to 2L),
        )
        val eng = engine(folder, blobs, space)
        assertEquals(2, eng.syncOnce().uploaded) // pushes 2 changes (the log was empty before this)
        eng.syncOnce() // folds its own 2 changes back in

        val baseline = space.served
        assertTrue(baseline > 0, "the engine must actually have read the log by now")

        // Three quiet passes: nothing changed anywhere, so nothing may cross the wire.
        repeat(3) { eng.syncOnce() }
        assertEquals(baseline, space.served, "a quiet pass re-downloaded the change log")
    }

    /**
     * Incremental folding must land on the SAME state as replaying the whole log — the property
     * that makes carrying the drive between passes safe ([Drive.apply] is a semilattice). If these
     * ever diverge, a long-running daemon's view of the vault silently drifts from a fresh one's.
     */
    @Test
    fun incrementalFoldMatchesAFullReplay() {
        val blobs = MemBlobStore()
        val space = MemSpace()

        // A long-running engine that folds incrementally across several passes.
        val folder = MemFolder()
        val incremental = engine(folder, blobs, space)
        val writer = MemFolder(mutableMapOf("x.txt" to "v1".encodeToByteArray()), mutableMapOf("x.txt" to 1L))
        val other = engine(writer, blobs, space)

        other.syncOnce() // push x.txt v1
        incremental.syncOnce() // fold it
        writer.files["x.txt"] = "v2-longer".encodeToByteArray()
        writer.mtimes["x.txt"] = 2L
        other.syncOnce() // push v2
        writer.files["y.txt"] = "why".encodeToByteArray()
        writer.mtimes["y.txt"] = 3L
        other.syncOnce() // push y.txt
        incremental.syncOnce() // fold the tail

        // A cold engine replaying the entire log from scratch.
        val coldFolder = MemFolder()
        engine(coldFolder, blobs, space).syncOnce()

        assertEquals(
            coldFolder.files.keys.sorted(),
            folder.files.keys.sorted(),
            "the incrementally folded drive disagrees with a full replay",
        )
        for (path in coldFolder.files.keys) {
            assertTrue(
                coldFolder.files.getValue(path).contentEquals(folder.files[path]),
                "$path differs between an incremental fold and a full replay",
            )
        }
    }

    private fun stateFile(): File = File(Files.createTempDirectory("drive-state").toFile(), "vault-index.drive.json")

    private fun persistentEngine(
        folder: VaultFolder,
        blobs: VaultBlobStore,
        space: VaultSpace,
        file: File,
        index: SyncIndexStore = InMemorySyncIndexStore(),
    ) = persistentEngineAt("http://x", folder, blobs, space, file, index)

    @Suppress("LongParameterList") // a test factory: each argument is one seam of the engine
    private fun persistentEngineAt(
        controller: String,
        folder: VaultFolder,
        blobs: VaultBlobStore,
        space: VaultSpace,
        file: File,
        index: SyncIndexStore = InMemorySyncIndexStore(),
    ) =
        VaultSyncEngine(
            folder,
            blobs,
            space,
            index,
            controller,
            Credential.Guest,
            "space-1",
            key,
            FileDriveStateStore(file),
        )

    /**
     * The restart contract (#73). A daemon that restarts with its state file resumes from the
     * cursor it saved and pulls NOTHING it already folded. Before, every restart re-pulled the
     * whole log, which on a throttled link was tens of minutes.
     *
     * SABOTAGE: drop the stateStore.load call in buildDrive. The restarted engine then starts
     * from 0 and `served` climbs by the whole log.
     */
    @Test
    fun aRestartedEngineResumesFromItsSavedCursor() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        val writer = MemFolder(
            mutableMapOf("a.txt" to "one".encodeToByteArray(), "b.txt" to "two".encodeToByteArray()),
            mutableMapOf("a.txt" to 1L, "b.txt" to 2L),
        )
        val writerEngine = engine(writer, blobs, space)
        writerEngine.syncOnce()

        val file = stateFile()
        val folder = MemFolder()
        val index = InMemorySyncIndexStore() // on disk too, in real life: it survives the restart
        assertEquals(2, persistentEngine(folder, blobs, space, file, index = index).syncOnce().downloaded)
        assertTrue(file.exists(), "the fold was saved")

        // A NEW engine instance over the same state file: the restart.
        val served = space.served
        val restarted = persistentEngine(folder, blobs, space, file, index = index)
        restarted.syncOnce()
        assertEquals(served, space.served, "a restart re-pulled changes it had already folded")

        // And it still sees the drive: a change after the restart folds onto the restored state.
        writer.files["c.txt"] = "three".encodeToByteArray()
        writer.mtimes["c.txt"] = 3L
        writerEngine.syncOnce()
        assertEquals(1, restarted.syncOnce().downloaded)
        assertEquals(setOf("a.txt", "b.txt", "c.txt"), folder.files.keys)
    }

    /** The cursor is one controller's arrival position; against another it means nothing. */
    @Test
    fun aRepointedEngineIgnoresTheSavedState() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        engine(MemFolder(mutableMapOf("a.txt" to "one".encodeToByteArray()), mutableMapOf("a.txt" to 1L)), blobs, space)
            .syncOnce()
        val file = stateFile()
        persistentEngine(MemFolder(), blobs, space, file).syncOnce()

        val served = space.served
        persistentEngineAt("http://elsewhere", MemFolder(), blobs, space, file).syncOnce()
        assertEquals(served + space.changes.size, space.served, "a repointed engine must start from 0")
    }

    /**
     * A push that throws leaves a local write in the in-memory drive that the server never got.
     * It must not reach the state file, or the next restart would treat it as remote.
     */
    @Test
    fun aFailedPassDoesNotPersistAWriteTheServerNeverGot() {
        val blobs = MemBlobStore()
        val space = MemSpace()
        engine(MemFolder(mutableMapOf("a.txt" to "one".encodeToByteArray()), mutableMapOf("a.txt" to 1L)), blobs, space)
            .syncOnce()

        val file = stateFile()
        val folder = MemFolder()
        val eng = persistentEngine(folder, blobs, space, file)
        eng.syncOnce() // folds a.txt, saves
        folder.files["b.txt"] = "local".encodeToByteArray()
        folder.mtimes["b.txt"] = 5L
        space.failNextPush = true
        assertFailsWith<IllegalStateException> { eng.syncOnce() }

        // Another change lands, so the next pass folds (and saves) again.
        engine(MemFolder(mutableMapOf("c.txt" to "c".encodeToByteArray()), mutableMapOf("c.txt" to 6L)), blobs, space)
            .syncOnce()
        val stats = eng.syncOnce()
        assertEquals(1, stats.uploaded, "b.txt was retried as a local upload, not mistaken for remote")
        val saved = assertNotNull(FileDriveStateStore(file).load("http://x", "space-1"))
        assertNull(saved.drive.get("b.txt"), "the saved fold predates b.txt's (successful) push")
    }
}
