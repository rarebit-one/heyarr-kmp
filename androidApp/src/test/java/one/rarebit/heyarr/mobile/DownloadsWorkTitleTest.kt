package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.ui.screens.workIdsToFetch
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsWorkTitleTest {

    @Test fun emptyWantedReturnsEmpty() {
        val result = workIdsToFetch(wanted = emptyList(), titles = emptyMap(), notFound = emptySet())
        assertEquals("nothing to fetch when wanted is empty", emptyList<String>(), result)
    }

    @Test fun unknownIdsAreReturned() {
        // ids not in titles and not in notFound must be fetched.
        val result = workIdsToFetch(
            wanted = listOf("a", "b", "c"),
            titles = emptyMap(),
            notFound = emptySet(),
        )
        assertEquals("all three ids queued", listOf("a", "b", "c"), result)
    }

    @Test fun alreadyResolvedIdsAreSkipped() {
        // ids that already have a title entry must not be re-fetched.
        val result = workIdsToFetch(
            wanted = listOf("a", "b", "c"),
            titles = mapOf("a" to "Work A", "c" to "Work C"),
            notFound = emptySet(),
        )
        assertEquals("only unresolved id queued", listOf("b"), result)
    }

    @Test fun notFoundIdsAreSkipped() {
        // ids that previously returned a 404 must not be retried.
        val result = workIdsToFetch(
            wanted = listOf("a", "b", "c"),
            titles = emptyMap(),
            notFound = setOf("b"),
        )
        assertEquals("404'd id skipped", listOf("a", "c"), result)
    }

    @Test fun titlesAndNotFoundCombine() {
        // ids resolved in titles AND ids in notFound are both excluded.
        val result = workIdsToFetch(
            wanted = listOf("a", "b", "c", "d"),
            titles = mapOf("a" to "Work A"),
            notFound = setOf("c"),
        )
        assertEquals("only genuinely missing ids queued", listOf("b", "d"), result)
    }

    @Test fun orderIsPreserved() {
        // The result order must match the order in wanted, not the set/map order.
        val result = workIdsToFetch(
            wanted = listOf("z", "m", "a"),
            titles = emptyMap(),
            notFound = emptySet(),
        )
        assertEquals("input order preserved", listOf("z", "m", "a"), result)
    }
}
