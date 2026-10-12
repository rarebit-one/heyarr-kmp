package one.rarebit.heyarr.core.library

/**
 * Resolves off-page canonical works for variant suppression (#134).
 *
 * [Variants.group] hides season-noise duplicates when their canonical work is already
 * in the loaded page. When a page carries the variant but not its canonical, the
 * variant stays visible. [resolveFor] fetches the missing canonicals one (base, kind)
 * pair at a time and caches the result for the lifetime of the screen.
 */
class CanonicalLookup {
    private val cache = HashMap<Pair<String, String?>, List<CatalogWork>>()

    /**
     * Returns the off-page canonical works for any variant in [works] whose canonical
     * is not already present. Calls [lookup] at most once per unique (base, kind) pair;
     * subsequent calls for the same key return the cached result.
     *
     * [lookup] receives the normalised base title and the kind string; it should call the
     * API's work-search endpoint and return whatever matches. The call happens on whatever
     * dispatcher the caller is running on — wrap it in `session.io { }` or equivalent if
     * [lookup] performs blocking I/O.
     */
    fun resolveFor(
        works: List<CatalogWork>,
        lookup: (q: String, kind: String?) -> List<CatalogWork>,
    ): List<CatalogWork> {
        // Works that are already grouped under a canonical on this page need no query —
        // their canonical is present and Variants.group will hide them correctly.
        val alreadyGrouped = Variants.group(works).values.flatten().map { it.id }.toSet()
        // Only titles that look like season variants have an off-page canonical to find;
        // each distinct (base, kind) is looked up once, ever.
        return works.asSequence()
            .filter { it.id !in alreadyGrouped }
            .mapNotNull { w -> Variants.split(w.title)?.let { it.base to w.kind } }
            .distinct()
            .flatMap { key -> cache.getOrPut(key) { lookup(key.first, key.second) } }
            .toList()
    }
}
