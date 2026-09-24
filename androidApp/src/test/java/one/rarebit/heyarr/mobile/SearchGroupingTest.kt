package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.mcp.SearchHitsJson
import one.rarebit.heyarr.mobile.preview.Fixtures
import one.rarebit.heyarr.mobile.search.FollowedSourcesJson
import one.rarebit.heyarr.mobile.state.SearchFilter
import one.rarebit.heyarr.mobile.state.SearchGrouping
import one.rarebit.heyarr.mobile.state.SearchRow
import one.rarebit.heyarr.mobile.state.Segment
import one.rarebit.heyarr.core.theme.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Grouping is pure: sections in a fixed order, per-type segments landing independently, rows re-homed by their own type. */
class SearchGroupingTest {

    private fun rows(query: String, type: String?) = SearchHitsJson.parse(Fixtures.searchContent(query, type)).works.map { SearchRow.WorkRow(it) }

    @Test
    fun sectionsFollowTheFixedOrderAndPendingSegmentsDoNotBlockOthers() {
        val segments = mapOf(
            MediaType.MOVIE to Segment.Loaded(rows("dune", "movie")),
            MediaType.SERIES to Segment.Pending,
            MediaType.MUSIC to Segment.Failed("indexer timeout"),
            MediaType.BOOK to Segment.Loaded(rows("dune", "book")),
        )
        val sections = SearchGrouping.group(segments, Segment.Loaded(emptyList()), Segment.Loaded(emptyList()))
        assertEquals(listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.PODCAST), sections.map { it.type })
        assertEquals(listOf("Dune: Part Two"), sections[0].rows.map { it.title })
        assertTrue(sections[1].segment is Segment.Pending)
        assertEquals("indexer timeout", (sections[2].segment as Segment.Failed).message)
        assertEquals(listOf("Dune"), sections[3].rows.map { it.title })
        assertFalse(SearchGrouping.settled(sections))
        assertFalse(SearchGrouping.empty(sections))
    }

    @Test
    fun rowsAreReHomedByTheirOwnTypeAndDeduplicated() {
        // A series work returned by the untyped call must land under Series, not Movies, and only once.
        val severance = rows("severance", null)
        val segments = mapOf(MediaType.MOVIE to Segment.Loaded(severance), MediaType.SERIES to Segment.Loaded(severance))
        val sections = SearchGrouping.group(segments, Segment.Loaded(emptyList()), Segment.Loaded(emptyList()))
        assertTrue(sections.first { it.type == MediaType.MOVIE }.rows.isEmpty())
        assertEquals(1, sections.first { it.type == MediaType.SERIES }.rows.size)
    }

    @Test
    fun episodesJoinSeriesAndFollowedSourcesJoinPodcasts() {
        val hits = SearchHitsJson.parse(Fixtures.searchContent("yellow", null))
        val episodes = Segment.Loaded(hits.episodes.map { SearchRow.EpisodeRow(it) })
        val sources = Segment.Loaded(SearchGrouping.matchSources("blog", FollowedSourcesJson.parse(Fixtures.followed)))
        val sections = SearchGrouping.group(emptyMap(), episodes, sources)
        assertEquals(listOf("S04E02 — Phantom Pain"), sections.first { it.type == MediaType.SERIES }.rows.map { it.title })
        assertEquals(listOf("Cloudflare Blog"), sections.first { it.type == MediaType.PODCAST }.rows.map { it.title })
        assertEquals(MediaType.FEED, sections.first { it.type == MediaType.PODCAST }.rows[0].type)
    }

    @Test
    fun filtersNarrowSectionsAndFlattenWalksReadingOrder() {
        val segments = MediaType.SEARCHABLE.associateWith { Segment.Loaded(rows("", it.apiName)) }
        val all = SearchGrouping.group(segments, Segment.Loaded(emptyList()), Segment.Loaded(emptyList()))
        val flat = SearchGrouping.flatten(all)
        assertEquals(flat.size, all.sumOf { it.rows.size })
        assertEquals(MediaType.MOVIE, flat.first().type)
        val books = SearchGrouping.group(segments, Segment.Loaded(emptyList()), Segment.Loaded(emptyList()), SearchFilter.BOOKS)
        assertEquals(listOf(MediaType.BOOK), books.map { it.type })
        assertEquals(3, books[0].rows.size)
        val nothing = SearchGrouping.group(MediaType.SEARCHABLE.associateWith { Segment.Loaded(emptyList()) }, Segment.Loaded(emptyList()), Segment.Loaded(emptyList()))
        assertTrue(SearchGrouping.empty(nothing))
    }
}
