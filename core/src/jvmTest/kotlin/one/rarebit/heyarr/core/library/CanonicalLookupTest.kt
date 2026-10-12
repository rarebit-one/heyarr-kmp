package one.rarebit.heyarr.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanonicalLookupTest {

    private data class TestWork(
        override val id: String,
        override val title: String,
        override val kind: String? = null,
    ) : CatalogWork

    @Test fun cacheHitMeansLookupCalledOncePerKey() {
        // Two variants with the same base title should produce exactly one API call — the
        // cache must serve the second call from its stored result (#134).
        val lookup = CanonicalLookup()
        var calls = 0
        val works: List<CatalogWork> = listOf(
            TestWork("ys4", "Yellowstone Season 4"),
            TestWork("ys5", "Yellowstone Season 5"),
        )
        // First call: the (base="yellowstone", kind=null) key is cold.
        lookup.resolveFor(works) { _, _ ->
            calls++
            emptyList()
        }
        // Second call: same key is now warm; the lambda must not be invoked again.
        lookup.resolveFor(works) { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(1, calls, "lookup called once per (base, kind) pair; cached on second call")
    }

    @Test fun alreadyLoadedCanonicalNotReQueried() {
        // When the canonical work is already on the page, Variants.group folds its season
        // variant under it. No API call should be made in that case.
        val lookup = CanonicalLookup()
        var calls = 0
        val works: List<CatalogWork> = listOf(
            TestWork("ys", "Yellowstone", "series"),
            TestWork("ys4", "Yellowstone Season 4", "series"),
        )
        lookup.resolveFor(works) { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(0, calls, "variant already grouped under its canonical — no lookup needed")
    }

    @Test fun emptyWorksReturnsEmpty() {
        val lookup = CanonicalLookup()
        assertTrue(lookup.resolveFor(emptyList()) { _, _ -> emptyList() }.isEmpty())
    }

    @Test fun nonVariantTitlesAreSkipped() {
        // Works with titles that have no season marker are not season variants; no lookup.
        val lookup = CanonicalLookup()
        var calls = 0
        val works: List<CatalogWork> = listOf(
            TestWork("m1", "Inception"),
            TestWork("m2", "The Dark Knight"),
        )
        lookup.resolveFor(works) { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(0, calls, "non-variant titles must not trigger a lookup")
    }

    @Test fun differentKindsProduceSeparateKeys() {
        // "Yellowstone Season 4" of kind "series" and "Yellowstone Season 4" of kind "special"
        // are different (base, kind) pairs and should each produce a lookup call.
        val lookup = CanonicalLookup()
        var calls = 0
        val works: List<CatalogWork> = listOf(
            TestWork("ys4s", "Yellowstone Season 4", "series"),
            TestWork("ys4x", "Yellowstone Season 4", "special"),
        )
        lookup.resolveFor(works) { _, _ ->
            calls++
            emptyList()
        }
        assertEquals(2, calls, "distinct kind values must produce distinct cache keys")
    }

    @Test fun queriesWithTheTitlesOwnSpelling() {
        // The node matches sort_title by substring, so punctuation must survive (Codex on #138).
        val asked = mutableListOf<String>()
        CanonicalLookup().resolveFor(listOf(TestWork("v", "9-1-1 Season 2"))) { q, _ ->
            asked += q
            emptyList()
        }
        assertEquals(listOf("9-1-1"), asked)
    }

    @Test fun aFailedLookupIsRetriedNotCached() {
        // A transient failure must not be cached as "no canonical" (Codex on #138).
        val lookup = CanonicalLookup()
        val works: List<CatalogWork> = listOf(TestWork("ys4", "Yellowstone Season 4"))
        var calls = 0
        val first = lookup.resolveFor(works) { _, _ ->
            calls++
            error("timeout")
        }
        assertTrue(first.isEmpty(), "a failure yields nothing this time")
        val canon = TestWork("ys", "Yellowstone")
        val second = lookup.resolveFor(works) { _, _ ->
            calls++
            listOf(canon)
        }
        assertEquals(2, calls, "the failed key is looked up again")
        assertEquals(listOf<CatalogWork>(canon), second)
    }
}
