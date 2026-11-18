package one.rarebit.heyarr.mobile

import android.os.SystemClock
import one.rarebit.heyarr.mobile.reader.ReadingPositionStore
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small EPUB for the reader's on-device tests (ReaderRestoreTest, ReaderErrorTest), built
 * here and served over loopback as the blob route serves it, and the wait for the navigator
 * to say where it opened.
 */
object TestEpub {
    /** Chapter two, as a Readium Locator, with no position: one the navigator has not made. */
    const val CHAPTER_TWO =
        """{"href":"OEBPS/ch2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0}}"""

    private const val PARAGRAPHS = 40
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 100L

    /**
     * Wait until the navigator has reported a place of its own (Readium adds where in the
     * book) in chapter two for [asset].
     */
    fun awaitOpenedInChapterTwo(positions: ReadingPositionStore, asset: String, what: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (true) {
            val now = positions.locator(asset).orEmpty()
            val ownReport = now.contains("\"position\"") || now.contains("totalProgression")
            if (ownReport && now.contains("ch2.xhtml")) return
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("timed out waiting: $what (kept: $now)")
            SystemClock.sleep(POLL_MS)
        }
    }

    /** A two-chapter EPUB 3: the `mimetype` entry first and stored, then the package. */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                },
            )
            zip.write(mimetype)
            for ((name, text) in packageFiles()) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
            }
        }
        return out.toByteArray()
    }

    private fun packageFiles(): Map<String, String> = mapOf(
        "META-INF/container.xml" to """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """,
        "OEBPS/content.opf" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="id">urn:uuid:3b7c2e9a-heyarr-reader-restore</dc:identifier>
                <dc:title>Book</dc:title>
                <dc:language>en</dc:language>
                <meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="ch1"/><itemref idref="ch2"/></spine>
            </package>
        """,
        "OEBPS/nav.xhtml" to """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body><nav epub:type="toc"><ol>
                <li><a href="ch1.xhtml">One</a></li><li><a href="ch2.xhtml">Two</a></li>
              </ol></nav></body>
            </html>
        """,
        "OEBPS/ch1.xhtml" to chapter(1),
        "OEBPS/ch2.xhtml" to chapter(2),
    )

    private fun chapter(n: Int) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $n</title></head>
          <body><h1>Chapter $n</h1>${"<p>Words on a page of chapter $n.</p>".repeat(PARAGRAPHS)}</body>
        </html>
    """
}
