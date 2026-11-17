package one.rarebit.heyarr.mobile.reader

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The PDF download and its content-addressed cache (#162), over an OkHttp that never leaves the JVM. */
class PdfDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val hex = "a".repeat(64)
    private val url = "https://node.example:7777/api/v1/blobs/blake3:$hex/content"
    private val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(200_000) { (it % 251).toByte() }

    private var requests = 0

    /** Answers every request with [code] and [body], as the node's blob route would. */
    private fun client(code: Int = 200, body: ByteArray = pdf): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                requests++
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message("x")
                    .body(body.toResponseBody("application/pdf".toMediaType()))
                    .build()
            },
        ).build()

    @Test fun downloadsIntoAFileNamedByTheBlobHashAndReportsProgress() = runBlocking {
        val dir = tmp.newFolder("reader-pdf")
        val seen = mutableListOf<Int>()
        val file = PdfDownload(client(), dir).fetch(url) { seen += it }
        assertEquals("$hex.pdf", file.name)
        assertArrayEquals(pdf, file.readBytes())
        assertEquals("progress ends complete", 100, seen.last())
        assertEquals("each percentage once", seen.distinct(), seen)
        assertFalse("no partial file left", File(dir, "$hex.pdf.part").exists())
    }

    @Test fun aCachedFileIsReusedWithoutAnotherRequest() = runBlocking {
        val dir = tmp.newFolder("reader-pdf")
        val download = PdfDownload(client(), dir)
        download.fetch(url)
        download.fetch(url)
        assertEquals("the second open is served from the cache", 1, requests)
    }

    @Test fun aRefusalIsReportedAndLeavesNothingCached() = runBlocking {
        val dir = tmp.newFolder("reader-pdf")
        try {
            PdfDownload(client(code = 401, body = ByteArray(0)), dir).fetch(url)
            fail("a 401 must not produce a file")
        } catch (e: PdfDownload.Failed) {
            assertEquals("HTTP 401", e.message)
        }
        assertTrue("nothing cached", dir.listFiles().orEmpty().isEmpty())
    }

    @Test fun onlyTheMostRecentPdfsAreKept() = runBlocking {
        val dir = tmp.newFolder("reader-pdf")
        val old = File(dir, "${"b".repeat(64)}.pdf").apply { writeText("old") }
        val older = File(dir, "${"c".repeat(64)}.pdf").apply { writeText("older") }
        val stalePart = File(dir, "${"d".repeat(64)}.pdf.part").apply { writeText("half") }
        old.setLastModified(2_000_000L)
        older.setLastModified(1_000_000L)
        PdfDownload(client(), dir, keep = 2).fetch(url)
        assertTrue("the opened file", File(dir, "$hex.pdf").isFile)
        assertTrue("the next most recent stays", old.isFile)
        assertFalse("evicted", older.exists())
        assertFalse("an abandoned download is swept", stalePart.exists())
    }

    @Test fun cacheNamesFollowTheContentNotTheNode() {
        assertEquals("$hex.pdf", PdfCache.fileNameFor(url))
        assertEquals(
            "another address for the same blob",
            "$hex.pdf",
            PdfCache.fileNameFor("http://10.0.2.2:7777/api/v1/blobs/blake3:$hex/content?x=1"),
        )
        val other = PdfCache.fileNameFor("https://node.example/some/file.pdf")
        assertTrue("any other URL gets a stable digest", Regex("^[0-9a-f]{64}\\.pdf$").matches(other))
        assertEquals(other, PdfCache.fileNameFor("https://node.example/some/file.pdf"))
    }

    @Test fun evictionKeepsTheCurrentFileWhateverItsAge() {
        val files = listOf("cur.pdf" to 1L, "a.pdf" to 30L, "b.pdf" to 20L, "c.pdf" to 10L, "cur.pdf.part" to 5L)
        assertEquals(setOf("b.pdf", "c.pdf"), PdfCache.toDelete(files, "cur.pdf", keep = 2))
        assertEquals(
            "keep 1 is the current file alone",
            setOf("a.pdf", "b.pdf", "c.pdf"),
            PdfCache.toDelete(files, "cur.pdf", keep = 1),
        )
    }

    @Test fun percentIsUnknownWithoutALength() {
        assertEquals(null, PdfCache.percent(10, -1))
        assertEquals(50, PdfCache.percent(50, 100))
        assertEquals(100, PdfCache.percent(150, 100))
    }
}
