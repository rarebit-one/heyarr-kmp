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
     * [lookup] receives the base title as the variant spells it, and the kind string; it should call the
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
        // Only titles that look like season variants have an off-page canonical to find.
        // Keyed on the normalised base, queried with the title's own spelling (`9-1-1`, not
        // `9 1 1`: the node matches sort_title by substring). Only a successful lookup is
        // cached; a failure (timeout, 401) is retried on the next call (Codex on #138).
        return works.asSequence()
            .filter { it.id !in alreadyGrouped }
            .mapNotNull { w ->
                val split = Variants.split(w.title) ?: return@mapNotNull null
                (split.base to w.kind) to (Variants.rawBase(w.title) ?: split.base)
            }
            .distinctBy { it.first }
            .flatMap { (key, raw) ->
                cache[key] ?: runCatching { lookup(raw, key.second) }
                    .onSuccess { cache[key] = it }
                    .getOrDefault(emptyList())
            }
            .toList()
    }
}
