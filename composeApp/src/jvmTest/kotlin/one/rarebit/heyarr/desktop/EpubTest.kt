package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.library.Epub
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The placeholder EPUB model: linearise (X)HTML to text, and follow the OPF spine in its
 * declared order. Pure and dependency-free — a synthetic archive built in-test.
 */
class EpubTest {

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun stripHtmlLinearisesDropsScriptAndUnescapes() {
        val html = "<html><head><style>x{color:red}</style></head><body>" +
            "<h1>Title</h1><p>Hello &amp; welcome.</p><p>Tom &#39;n&#39; Jerry</p><script>bad()</script></body></html>"
        val text = Epub.stripHtml(html)
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("Hello & welcome."))
        assertTrue(text.contains("Tom 'n' Jerry"))
        assertFalse(text.contains("bad()"))
        assertFalse(text.contains("<"))
    }

    @Test fun parsesTitleAndSpineInDeclaredOrder() {
        val container = """<?xml version="1.0"?><container><rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
            </rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package><metadata><dc:title>The Placeholder</dc:title></metadata>
            <manifest>
              <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
              <item id="c2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
            </manifest>
            <spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>"""
        val bytes = zip(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf,
            "OEBPS/ch1.xhtml" to "<html><body><h2>One</h2><p>First body.</p></body></html>",
            "OEBPS/ch2.xhtml" to "<html><body><h2>Two</h2><p>Second body.</p></body></html>",
        )
        val book = Epub.parse(bytes)
        assertNotNull(book)
        assertEquals("The Placeholder", book.title)
        assertEquals(2, book.chapters.size)
        assertEquals("Two", book.chapters[0].title) // spine ordered c2 before c1
        assertTrue(book.chapters[0].text.contains("Second body."))
        assertEquals("One", book.chapters[1].title)
    }

    @Test fun nonEpubReturnsNull() {
        assertNull(Epub.parse("not a zip at all".toByteArray()))
    }
}
