package one.rarebit.heyarr.desktop.library

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A dependency-free, PLACEHOLDER EPUB reader model: unzip in memory, follow the OPF
 * spine, and reduce each XHTML document to linear text. Not a rendering engine — no CSS,
 * images or pagination — just a readable pass so the desktop has *a* reader while a real
 * one is built (heyarr-mobile's `reader/` is the reference). Parsed with regex in the
 * same no-XML-library spirit as the app's hand-rolled `JsonScan`; malformed or non-EPUB
 * input yields null (the screen then offers "open externally") rather than throwing.
 */
object Epub {
    data class Chapter(val title: String, val text: String)
    data class Book(val title: String?, val chapters: List<Chapter>)

    /** Parse EPUB [bytes] into a linear-text [Book], or null when it is not a usable EPUB. */
    fun parse(bytes: ByteArray): Book? {
        val entries = runCatching { unzip(bytes) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        val opfPath = opfPath(entries) ?: return null
        val opf = entries[opfPath]?.let { String(it, Charsets.UTF_8) } ?: return null
        val baseDir = opfPath.substringBeforeLast('/', "")
        val manifest = manifest(opf)
        val title = tag(opf, "dc:title") ?: tag(opf, "title")
        val chapters = spine(opf).mapNotNull { idref ->
            val href = manifest[idref] ?: return@mapNotNull null
            val doc = entries[resolve(baseDir, href.substringBefore('#'))]?.let { String(it, Charsets.UTF_8) } ?: return@mapNotNull null
            val text = stripHtml(doc)
            if (text.isBlank()) null else Chapter(heading(doc) ?: idref, text)
        }
        return if (chapters.isEmpty()) null else Book(title?.takeIf { it.isNotBlank() }, chapters)
    }

    /** Reduce an (X)HTML document to linear text: drop head/script/style, block ends → newlines, unescape entities. */
    fun stripHtml(html: String): String {
        var s = RE_DROP.replace(html, " ")
        s = RE_BREAK.replace(s, "\n")
        s = RE_TAG.replace(s, "")
        s = unescape(s)
        return s.lines().joinToString("\n") { it.trim() }.replace(RE_BLANKS, "\n\n").trim()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zin.readBytes()
                zin.closeEntry()
            }
        }
        return out
    }

    /** The OPF path from META-INF/container.xml, else the first *.opf in the archive. */
    private fun opfPath(entries: Map<String, ByteArray>): String? {
        entries["META-INF/container.xml"]?.let { c ->
            RE_ROOTFILE.find(String(c, Charsets.UTF_8))?.groupValues?.get(1)?.let { return it }
        }
        return entries.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
    }

    private fun manifest(opf: String): Map<String, String> = RE_ITEM.findAll(opf).mapNotNull { m ->
        val id = attr(m.value, "id") ?: return@mapNotNull null
        val href = attr(m.value, "href") ?: return@mapNotNull null
        id to href
    }.toMap()

    private fun spine(opf: String): List<String> {
        val spine = RE_SPINE.find(opf)?.value ?: return emptyList()
        return RE_ITEMREF.findAll(spine).mapNotNull { attr(it.value, "idref") }.toList()
    }

    /** Resolve [href] against the OPF's directory, collapsing `.`/`..` segments. */
    private fun resolve(baseDir: String, href: String): String {
        val parts = ArrayList<String>()
        (baseDir.split('/') + href.trim().split('/')).forEach { seg ->
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    private fun heading(html: String): String? =
        RE_HEADING.find(html)?.groupValues?.get(2)?.let { stripHtml(it) }?.takeIf { it.isNotBlank() && it.length <= 120 }

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace(RE_NUMERIC) { m -> m.groupValues[1].toIntOrNull()?.let { it.toChar().toString() } ?: m.value }

    private fun attr(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)

    private fun tag(xml: String, name: String): String? =
        Regex("""<$name\b[^>]*>(.*?)</$name>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(xml)?.groupValues?.get(1)?.let { stripHtml(it) }

    private val RE_ROOTFILE = Regex("""full-path\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val RE_ITEM = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RE_SPINE = Regex("""<spine\b.*?</spine>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_ITEMREF = Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val RE_HEADING = Regex("""<(h[1-3])\b[^>]*>(.*?)</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_DROP = Regex("""<(script|style|head)\b.*?</\1>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_BREAK = Regex("""<br\s*/?>|</(p|div|h[1-6]|li|tr|section|article|blockquote)>""", RegexOption.IGNORE_CASE)
    private val RE_TAG = Regex("""<[^>]+>""")
    private val RE_BLANKS = Regex("""\n{3,}""")
    private val RE_NUMERIC = Regex("""&#(\d{1,6});""")
}
