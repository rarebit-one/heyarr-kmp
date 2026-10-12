package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.ui.screens.HOME_PAGE_CAP
import one.rarebit.heyarr.mobile.ui.screens.RECENT_QUOTA
import one.rarebit.heyarr.mobile.ui.screens.SPOTLIGHT_QUOTA
import one.rarebit.heyarr.mobile.ui.screens.homeRails
import one.rarebit.heyarr.mobile.ui.screens.quotasMet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the home-rail helpers extracted by #135.
 * These exercise [homeRails] and [quotasMet] without needing Compose or an Android runtime.
 */
class HomeRailsTest {

    private fun work(id: String, kind: String? = "series") = Work(id = id, title = id, kind = kind)

    @Test fun emptyWorksProducesEmptyRails() {
        val (spotlight, recent) = homeRails(emptyList())
        assertEquals(0, spotlight.size)
        assertEquals(0, recent.size)
        assertFalse(quotasMet(emptyList()))
    }

    @Test fun spotlightCapsAtSpotlightQuota() {
        val works = (1..30).map { work("w$it") }
        val (spotlight, _) = homeRails(works)
        assertEquals(SPOTLIGHT_QUOTA, spotlight.size)
    }

    @Test fun recentCapsAtRecentQuota() {
        val works = (1..30).map { work("w$it") }
        val (_, recent) = homeRails(works)
        assertEquals(RECENT_QUOTA, recent.size)
    }

    @Test fun documentsAreExcludedFromBothRails() {
        val works = listOf(work("d1", kind = "document"), work("w1"))
        val (spotlight, recent) = homeRails(works)
        assertTrue(spotlight.all { it.kind != "document" })
        assertTrue(recent.all { it.kind != "document" })
    }

    @Test fun unknownKindExcludedFromSpotlightButIncludedInRecent() {
        val works = listOf(work("u1", kind = "unknown"), work("w1"))
        val (spotlight, recent) = homeRails(works)
        assertTrue(spotlight.none { it.kind == "unknown" })
        assertTrue(recent.any { it.kind == "unknown" })
    }

    @Test fun quotasMetOnlyWhenBothFilled() {
        val few = (1..5).map { work("w$it") }
        assertFalse(quotasMet(few))
        // RECENT_QUOTA is the larger constraint; need at least that many non-document works
        // and at least SPOTLIGHT_QUOTA non-document non-unknown works.
        val many = (1..(RECENT_QUOTA + 1)).map { work("w$it") }
        assertTrue(quotasMet(many))
    }

    @Test fun documentsDoNotCountTowardQuota() {
        // A page with RECENT_QUOTA documents is nowhere near the quota.
        val docs = (1..RECENT_QUOTA).map { work("d$it", kind = "document") }
        assertFalse(quotasMet(docs))
    }

    @Test fun constantsAreSane() {
        assertTrue("spotlight quota must be positive", SPOTLIGHT_QUOTA > 0)
        assertTrue("recent quota must be >= spotlight quota", RECENT_QUOTA >= SPOTLIGHT_QUOTA)
        assertTrue("page cap must be at least 1", HOME_PAGE_CAP >= 1)
    }
}
