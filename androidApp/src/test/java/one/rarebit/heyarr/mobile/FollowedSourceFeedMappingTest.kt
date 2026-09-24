package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.state.SearchGrouping
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.mobile.search.asFeedSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone's followed source reaches `:core`'s search grouping with what search reads intact. */
class FollowedSourceFeedMappingTest {
    private val full = FollowedSource(
        id = "fs-1",
        title = "Cloudflare Blog",
        workId = "w-cloudflare",
        type = "feed",
        itemsKnown = 12,
        itemsArchived = 9,
        health = "healthy",
        feedRef = "https://blog.cloudflare.com/rss/",
        qualityProfileId = "hd",
        monitor = true,
        lastPolledAt = "2026-09-01T00:00:00Z",
    )

    @Test fun carriesEveryFieldSearchReads() {
        val core = full.asFeedSource()
        assertEquals("fs-1", core.id)
        assertEquals("Cloudflare Blog", core.title)
        assertEquals("w-cloudflare", core.workId)
        assertEquals("feed", core.type)
        assertEquals(12, core.itemsKnown)
        assertEquals(9, core.itemsArchived)
        assertEquals("healthy", core.health)
        assertEquals("https://blog.cloudflare.com/rss/", core.feedRef)
    }

    @Test fun absentCountersReadAsZeroAsTheSearchRowShowedThem() {
        val core = FollowedSource(id = "fs-2", title = "t").asFeedSource()
        assertEquals(0, core.itemsKnown)
        assertEquals(0, core.itemsArchived)
    }

    @Test fun matchesByTitleOrFeedRefThroughTheSharedGrouping() {
        val other = FollowedSource(id = "fs-3", title = "Other", feedRef = "https://example.test/feed")
        val sources = listOf(full, other).map { it.asFeedSource() }
        assertEquals(listOf("source:fs-1"), SearchGrouping.matchSources("cloudflare", sources).map { it.key })
        val byRef = SearchGrouping.matchSources("example.test", sources).single()
        assertEquals("fs-3", (byRef as SearchRow.SourceRow).source.id)
    }
}
