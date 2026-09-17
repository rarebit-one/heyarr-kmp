package one.rarebit.heyarr.core.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncReconcileTest {

    private fun lf(hash: String, size: Long = 1, mtime: Long = 1) = LocalFile(size, mtime, "blake3:$hash")
    private fun entry(path: String, blob: String) = DriveEntry(path, "blake3:$blob", 1, 1, false)
    private fun idx(phash: String, blob: String) = SyncIndexEntry("blake3:$phash", "blake3:$blob", 1, 1)

    @Test
    fun newLocalUploads() {
        val a = reconcile(mapOf("a" to lf("p1")), emptyMap(), emptyMap())
        assertEquals(listOf(SyncAction.UploadLocal("a")), a)
    }

    @Test
    fun newRemoteDownloads() {
        val a = reconcile(emptyMap(), mapOf("a" to entry("a", "c1")), emptyMap())
        assertEquals(listOf(SyncAction.DownloadRemote("a", "blake3:c1")), a)
    }

    @Test
    fun unchangedIsNoop() {
        val a = reconcile(
            mapOf("a" to lf("p1")),
            mapOf("a" to entry("a", "c1")),
            mapOf("a" to idx("p1", "c1")),
        )
        assertTrue(a.isEmpty())
    }

    @Test
    fun localEditUploads() {
        val a = reconcile(
            mapOf("a" to lf("p2")),
            mapOf("a" to entry("a", "c1")),
            mapOf("a" to idx("p1", "c1")),
        )
        assertEquals(listOf(SyncAction.UploadLocal("a")), a)
    }

    @Test
    fun remoteEditDownloads() {
        val a = reconcile(
            mapOf("a" to lf("p1")),
            mapOf("a" to entry("a", "c2")),
            mapOf("a" to idx("p1", "c1")),
        )
        assertEquals(listOf(SyncAction.DownloadRemote("a", "blake3:c2")), a)
    }

    @Test
    fun localDeleteWithUnchangedRemotePropagates() {
        val a = reconcile(
            emptyMap(),
            mapOf("a" to entry("a", "c1")),
            mapOf("a" to idx("p1", "c1")),
        )
        assertEquals(listOf(SyncAction.DeleteRemote("a")), a)
    }

    @Test
    fun remoteDeleteWithUnchangedLocalRemovesLocal() {
        val a = reconcile(
            mapOf("a" to lf("p1")),
            emptyMap(),
            mapOf("a" to idx("p1", "c1")),
        )
        assertEquals(listOf(SyncAction.DeleteLocal("a")), a)
    }

    @Test
    fun bothEditedUploadsLocalAndLetsCrdtConflict() {
        val a = reconcile(
            mapOf("a" to lf("p2")),
            mapOf("a" to entry("a", "c2")),
            mapOf("a" to idx("p1", "c1")),
        )
        assertEquals(listOf(SyncAction.UploadLocal("a")), a)
    }

    @Test
    fun conflictedCopyPathIsDownloaded() {
        // resolved() invents this path; it's new to local + index → materialise on disk.
        val cc = "a (conflicted copy — wb — 3).txt"
        val a = reconcile(
            emptyMap(),
            mapOf(cc to entry(cc, "c9")),
            emptyMap(),
        )
        assertEquals(listOf(SyncAction.DownloadRemote(cc, "blake3:c9")), a)
    }

    @Test
    fun firstRunNeverDeletes_emptyLocalPopulatedRemote() {
        // Empty index + empty local folder + populated remote: download only, NO deletes.
        val a = reconcile(emptyMap(), mapOf("a" to entry("a", "c1"), "b" to entry("b", "c2")), emptyMap())
        assertEquals(
            listOf(SyncAction.DownloadRemote("a", "blake3:c1"), SyncAction.DownloadRemote("b", "blake3:c2")),
            a,
        )
        assertTrue(a.none { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal })
    }

    @Test
    fun firstRunNeverDeletes_populatedLocalEmptyRemote() {
        // Empty index + populated local + empty remote: upload only, NO deletes.
        val a = reconcile(mapOf("a" to lf("p1"), "b" to lf("p2")), emptyMap(), emptyMap())
        assertEquals(listOf(SyncAction.UploadLocal("a"), SyncAction.UploadLocal("b")), a)
        assertTrue(a.none { it is SyncAction.DeleteRemote || it is SyncAction.DeleteLocal })
    }
}
