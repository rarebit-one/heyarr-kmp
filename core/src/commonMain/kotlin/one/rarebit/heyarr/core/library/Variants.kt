package one.rarebit.heyarr.core.library

/** What [Variants] (and [Series.playTitle]) need to know about a catalogue work; each app's `Work` implements it. */
interface CatalogWork {
    val id: String
    val title: String
    val kind: String?
}

/**
 * The scanner mints a separate series work for a download folder named
 * `<Series> Season N <release noise>` (heyarr-core#470), so a library shows
 * "Yellowstone", "Yellowstone Season 4 Mp4" and "Yellowstone Season 5 Mp4" as three
 * shows. Until the node converges them, the client folds such **variants** under the
 * canonical work: hidden from listings, listed on the canonical work's Curate tab as
 * "also catalogued as", never deleted or renamed — a stopgap over a catalog fact.
 *
 * A variant is a work whose title, lowercased, is `<base> season <n>` followed by
 * optional noise, where a work titled `<base>` of the same kind exists. Pure; tested.
 */
object Variants {
    private val RE = Regex("""^(.+?)\s+(?:season|series|s)\s*(\d{1,3})(?:\s.*)?$""", RegexOption.IGNORE_CASE)

    data class Split(val base: String, val season: Int)

    /** `"Yellowstone Season 4 Mp4 1080p"` → base `yellowstone`, season 4; null when the title has no season marker. */
    fun split(title: String): Split? {
        val m = RE.matchEntire(title.trim()) ?: return null
        return Split(norm(m.groupValues[1]), m.groupValues[2].toInt())
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /** canonical work id → its variant works, for every canonical work that has any. */
    fun <W : CatalogWork> group(works: List<W>): Map<String, List<W>> {
        val byKey = works.groupBy { (it.kind ?: "") to norm(it.title) }
        val out = LinkedHashMap<String, MutableList<W>>()
        for (w in works) {
            val s = split(w.title) ?: continue
            val canon = byKey[(w.kind ?: "") to s.base]?.firstOrNull { it.id != w.id } ?: continue
            out.getOrPut(canon.id) { ArrayList() }.add(w)
        }
        return out
    }

    /** The ids that are somebody's variant — what a listing hides. */
    fun variantIds(works: List<CatalogWork>): Set<String> = group(works).values.flatten().map { it.id }.toSet()

    /** The season a variant covers, for a label like "Season 4 · 7 files". */
    fun seasonOf(w: CatalogWork): Int? = split(w.title)?.season
}
