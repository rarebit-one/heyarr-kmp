package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.open.BlobDownloader
import one.rarebit.heyarr.desktop.open.DownloadResult
import one.rarebit.heyarr.desktop.open.ExternalOpener
import one.rarebit.heyarr.desktop.open.MediaExt
import one.rarebit.heyarr.desktop.open.OpenExternally
import one.rarebit.heyarr.desktop.open.OpenResult
import one.rarebit.heyarr.desktop.open.XdgOpen
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The open seams, proven WITHOUT launching xdg-open or touching the network: the exact
 * argv [XdgOpen] would spawn, and the composed download→open flow through fakes.
 */
class OpenExternallyTest {

    @Test
    fun xdgOpenSpawnsExactArgv() {
        val captured = ArrayList<List<String>>()
        val opener = XdgOpen(command = "xdg-open", available = { true }, spawn = { captured += it })
        val file = File("/tmp/heyarr-abc.epub")

        val result = opener.open(file)

        assertEquals(OpenResult.Opened, result)
        assertEquals(listOf("xdg-open", file.absolutePath), captured.single())
    }

    @Test
    fun xdgOpenMissingIsReportedNotSpawned() {
        var spawned = false
        val opener = XdgOpen(available = { false }, spawn = { spawned = true })
        val result = opener.open(File("/tmp/x.pdf"))
        assertTrue(result is OpenResult.Failed)
        assertTrue(!spawned)
    }

    @Test
    fun xdgOpenIoExceptionFailsCleanly() {
        val opener = XdgOpen(available = { true }, spawn = { throw IOException("boom") })
        val result = opener.open(File("/tmp/x.pdf"))
        assertTrue(result is OpenResult.Failed)
    }

    @Test
    fun openExternallyDownloadsThenOpensWithDerivedExtension() {
        var downloadedExt: String? = null
        val downloaded = File("/tmp/heyarr-xyz.epub")
        val fakeDownloader = object : BlobDownloader {
            override fun download(baseUrl: String, blobHash: String, token: String, ext: String): DownloadResult {
                downloadedExt = ext
                return DownloadResult.Downloaded(downloaded)
            }
        }
        val opened = ArrayList<File>()
        val fakeOpener = object : ExternalOpener {
            override fun open(file: File): OpenResult {
                opened += file
                return OpenResult.Opened
            }
        }

        val status = OpenExternally(fakeDownloader, fakeOpener).open(
            baseUrl = "https://h.example",
            blobHash = "blake3:${"a".repeat(64)}",
            token = "secret",
            filename = "the-dispossessed.epub",
            mime = "application/epub+zip",
            displayName = "The Dispossessed",
        )

        assertEquals("epub", downloadedExt) // extension from filename
        assertEquals(downloaded, opened.single())
        assertTrue(status.contains("The Dispossessed"))
    }

    @Test
    fun openExternallyReturnsDownloadFailureWithoutOpening() {
        val fakeDownloader = object : BlobDownloader {
            override fun download(baseUrl: String, blobHash: String, token: String, ext: String) =
                DownloadResult.Failed("Download failed: HTTP 404.")
        }
        var openCalled = false
        val fakeOpener = object : ExternalOpener {
            override fun open(file: File): OpenResult {
                openCalled = true
                return OpenResult.Opened
            }
        }
        val status = OpenExternally(fakeDownloader, fakeOpener)
            .open("https://h.example", "blake3:${"a".repeat(64)}", "s", null, "text/html", "Article")
        assertTrue(status.contains("404"))
        assertTrue(!openCalled)
    }

    @Test
    fun mediaExtPrefersFilenameThenMime() {
        assertEquals("pdf", MediaExt.forNameAndMime("book.PDF", "application/octet-stream"))
        assertEquals("html", MediaExt.forNameAndMime(null, "text/html; charset=utf-8"))
        assertEquals("epub", MediaExt.forNameAndMime(null, "application/epub+zip"))
        assertEquals("cbz", MediaExt.forNameAndMime(null, "application/vnd.comicbook+zip"))
        assertEquals("bin", MediaExt.forNameAndMime(null, null))
    }
}
