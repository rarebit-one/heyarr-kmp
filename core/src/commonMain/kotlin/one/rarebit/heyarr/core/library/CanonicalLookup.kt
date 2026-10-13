package one.rarebit.heyarr.core.library

/**
 * The result of a [CanonicalLookup.resolveFor] call.
 *
 * [resolved] is the list of off-page canonical works (same semantics as the old return value).
 * [complete] is false when at least one canonical lookup failed (network error, 401, …). A
 * false value means some variant canonicals are still unknown, so the caller must not treat
 * quota-satisfaction as a hard stop — later pages may surface canonicals that collapse
 * currently-visible variants (#139).
 */
data class Resolution(val resolved: List<CatalogWork>, val complete: Boolean)

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
     * is not already present, plus a [Resolution.complete] flag. Calls [lookup] at most
     * once per unique (base, kind) pair; subsequent calls for the same key return the
     * cached result.
     *
     * [Resolution.complete] is false when any lookup threw — the key is not cached, so the
     * next call retries it. Callers that use this for quota-gated paging should not stop
     * on quotas while [complete] is false, because a later page may return the canonical
     * that collapses one of the currently-visible variants (#139).
     *
     * [lookup] receives the base title as the variant spells it, and the kind string; it should call the
     * API's work-search endpoint and return whatever matches. The call happens on whatever
     * dispatcher the caller is running on — wrap it in `session.io { }` or equivalent if
     * [lookup] performs blocking I/O.
     */
    fun resolveFor(works: List<CatalogWork>, lookup: (q: String, kind: String?) -> List<CatalogWork>): Resolution {
        // Works that are already grouped under a canonical on this page need no query —
        // their canonical is present and Variants.group will hide them correctly.
        val alreadyGrouped = Variants.group(works).values.flatten().map { it.id }.toSet()
        // Only titles that look like season variants have an off-page canonical to find.
        // Keyed on the normalised base, queried with the title's own spelling (`9-1-1`, not
        // `9 1 1`: the node matches sort_title by substring). Only a successful lookup is
        // cached; a failure (timeout, 401) is retried on the next call (Codex on #138).
        val keys = works.asSequence()
            .filter { it.id !in alreadyGrouped }
            .mapNotNull { w ->
                val split = Variants.split(w.title) ?: return@mapNotNull null
                (split.base to w.kind) to (Variants.rawBase(w.title) ?: split.base)
            }
            .distinctBy { it.first }
            .toList()
        var anyFailed = false
        val resolved = keys.flatMap { (key, raw) ->
            cache[key] ?: runCatching { lookup(raw, key.second) }
                .onSuccess { cache[key] = it }
                .onFailure { anyFailed = true }
                .getOrDefault(emptyList())
        }
        return Resolution(resolved, complete = !anyFailed)
    }

    /**
     * Clears all cached lookups. Call at the start of each Refresh / [load] so a rescan
     * that adds or removes canonical works is reflected immediately rather than being
     * shadowed by stale cache entries (#142).
     */
    fun clear() {
        cache.clear()
    }
}
