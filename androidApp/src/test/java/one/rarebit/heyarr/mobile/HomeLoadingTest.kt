package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.ui.screens.HOME_PAGE_CAP
import one.rarebit.heyarr.mobile.ui.screens.loadDone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [loadDone] helper and the artwork-size constants introduced by #139
 * and #142. These require no Compose, no Android runtime and no network — just a JVM.
 */
class HomeLoadingTest {
    private val page = Any()

    // --- loadDone: incomplete resolution must keep paging ---

    @Test fun incompleteResolutionKeepsPaging() {
        // When resolveFor returns complete=false at least one lookup failed. Even if quotas
        // look satisfied with the current visible set, a later page may return the canonical
        // that collapses one of those variants — so paging must continue (#139).
        assertFalse(
            "incomplete + quotasMet must not stop paging",
            loadDone(page, "cursor", pages = 1, complete = false, quotasMet = true),
        )
    }

    @Test fun completedResolutionStopsOnQuotas() {
        // All lookups succeeded and both quotas are filled — safe to stop.
        assertTrue(
            "complete + quotasMet must stop paging",
            loadDone(page, "cursor", pages = 1, complete = true, quotasMet = true),
        )
    }

    @Test fun incompleteResolutionAlsoKeepsPagingWhenQuotasNotMet() {
        // incomplete + quotas not met: definitely keep paging (belts and braces).
        assertFalse(
            "incomplete + !quotasMet must not stop paging",
            loadDone(page, "cursor", pages = 1, complete = false, quotasMet = false),
        )
    }

    @Test fun completeResolutionWithoutQuotasKeepsPaging() {
        // All lookups succeeded but quotas are not yet filled — fetch more.
        assertFalse(
            "complete + !quotasMet must not stop paging",
            loadDone(page, "cursor", pages = 1, complete = true, quotasMet = false),
        )
    }

    // --- loadDone: hard stops always apply regardless of resolution state ---

    @Test fun hardStopsAlwaysApply() {
        // A null page means the API returned nothing new — stop regardless of everything else.
        assertTrue("null page stops", loadDone(null, "cursor", 1, complete = false, quotasMet = false))
        // A null cursor means there are no more pages on the server.
        assertTrue("null cursor stops", loadDone(page, null, 1, complete = false, quotasMet = false))
        // Hitting the page cap prevents runaway fetching even with an incomplete resolution.
        assertTrue("page cap stops", loadDone(page, "cursor", HOME_PAGE_CAP, complete = false, quotasMet = false))
    }

    // --- artwork constants: wide dp exceeds standard dp (#142) ---

    @Test fun wideCardArtworkDpIsLargerThanStandard() {
        // WIDE_CARD_ARTWORK_DP must always be strictly greater than CARD_ARTWORK_DP, because the
        // whole point of the constant is to reach a higher ADR-0109 bucket at the same density.
        assertTrue(
            "WIDE_CARD_ARTWORK_DP must exceed CARD_ARTWORK_DP",
            Artwork.WIDE_CARD_ARTWORK_DP > Artwork.CARD_ARTWORK_DP,
        )
    }

    @Test fun wideConstantReachesNextBucketAtTwoXDensity() {
        // At 2× density the standard 140 dp card asks for 280 px (512-bucket); the wide 280 dp
        // card asks for 560 px — which lands in the 1024-px bucket (ADR-0109, #142).
        val sizes = mapOf("256" to "/a", "512" to "/b", "1024" to "/c")
        val w = Work(id = "x", title = "X", artworkSizes = sizes)
        val wideAtTwoX = Artwork.WIDE_CARD_ARTWORK_DP * 2 // 560 px
        val standardAtTwoX = Artwork.CARD_ARTWORK_DP * 2 // 280 px
        val widePath = w.artworkFor(wideAtTwoX)
        val standardPath = w.artworkFor(standardAtTwoX)
        assertTrue("wide at 2× must reach the 1024 bucket", widePath == "/c")
        assertTrue("standard at 2× must use the 512 bucket", standardPath == "/b")
    }

    // --- grid cells: artwork is sized to the cell, not a nominal card (#142) ---

    @Test fun adaptiveCellMatchesComposeArithmetic() {
        // A 411 dp phone with a 160 dp minimum and 12 dp gaps fits two columns of 199 dp.
        assertEquals("two poster columns", 199, Artwork.adaptiveCellDp(411, 160, 12))
        // A 280 dp minimum fits once, so the single column fills the width.
        assertEquals("one wide column fills the row", 411, Artwork.adaptiveCellDp(411, 280, 12))
        // Narrower than the minimum still yields one column, never zero.
        assertEquals("never zero columns", 100, Artwork.adaptiveCellDp(100, 160, 12))
    }
}
