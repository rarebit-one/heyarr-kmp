package one.rarebit.heyarr.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.mcp.EpisodeHit
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.core.mcp.SearchHits
import one.rarebit.heyarr.core.state.SearchBackend
import one.rarebit.heyarr.core.state.SearchController
import one.rarebit.heyarr.core.state.SearchFilter
import one.rarebit.heyarr.core.state.SearchRow
import one.rarebit.heyarr.core.state.Segment
import one.rarebit.heyarr.core.theme.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {

    /** A scripted backend: one hit per kind, one episode, and a call log to assert the fan-out. */
    private class FakeBackend(
        var sources: List<FollowedSource> = listOf(FollowedSource(id = "s1", title = "Dune Pod", type = "podcast")),
        var failKind: MediaType? = null,
        var failure: Throwable = IllegalStateException("boom"),
        var failFollowed: Boolean = false,
    ) : SearchBackend {
        val searches = mutableListOf<Pair<String, MediaType?>>()
        var followedCalls = 0

        override fun searchContent(query: String, type: MediaType?): SearchHits {
            searches += query to type
            if (type != null && type == failKind) throw failure
            return if (type == null) {
                SearchHits(emptyList(), listOf(episode("e-$query")), truncated = false)
            } else {
                val works = listOf(work("${type.apiName}-$query", type))
                SearchHits(works, emptyList(), truncated = type == MediaType.MUSIC)
            }
        }

        override fun followedSources(): List<FollowedSource> {
            followedCalls++
            check(!failFollowed) { "offline" }
            return sources
        }
    }

    private fun TestScope.controller(
        backend: SearchBackend?,
        failures: MutableList<McpTransportException> = mutableListOf(),
    ) = SearchController(
        // The test scope itself, not backgroundScope: advanceUntilIdle skips background work.
        scope = this,
        backend = { backend },
        onTransportFailure = { failures += it },
        ioDispatcher = StandardTestDispatcher(testScheduler),
        debounceMs = 220,
    )

    @Test
    fun debouncesKeystrokesIntoOneFanOut() = runTest {
        val backend = FakeBackend()
        val c = controller(backend)

        c.updateQuery("du")
        advanceTimeBy(100)
        c.updateQuery("dune")
        assertEquals("dune", c.state.value.query, "the query is recorded synchronously")
        advanceTimeBy(219)
        runCurrent()
        assertTrue(backend.searches.isEmpty(), "nothing is sent before the debounce elapses")

        advanceUntilIdle()
        assertEquals(
            MediaType.SEARCHABLE.map { "dune" to it } + ("dune" to null),
            backend.searches,
            "one call per searchable kind plus one untyped call, for the final query only",
        )
    }

    @Test
    fun eachKindLandsInItsOwnSegment() = runTest {
        val c = controller(FakeBackend())
        c.updateQuery("dune")
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(MediaType.SEARCHABLE.toSet(), s.segments.keys)
        for (kind in MediaType.SEARCHABLE) {
            val seg = assertIs<Segment.Loaded>(s.segments.getValue(kind))
            assertEquals(listOf("${kind.apiName}-dune"), seg.rows.map { (it as SearchRow.WorkRow).hit.workId })
            assertEquals(kind == MediaType.MUSIC, seg.truncated, "truncation is carried per kind")
        }
        assertEquals(listOf("episode:e-dune"), assertIs<Segment.Loaded>(s.episodes).rows.map { it.key })
        assertEquals(listOf("source:s1"), assertIs<Segment.Loaded>(s.sources).rows.map { it.key })
        assertTrue(s.isSettled)
    }

    @Test
    fun segmentsArePendingUntilTheirCallReturns() = runTest {
        val c = controller(FakeBackend())
        c.updateQuery("dune")
        c.submit()
        // The fan-out has started but no call has run yet: every section says so rather than
        // showing as empty.
        val s = c.state.value
        assertEquals(MediaType.SEARCHABLE.associateWith { Segment.Pending }, s.segments)
        assertEquals(Segment.Pending, s.episodes)
        assertEquals(Segment.Pending, s.sources)
        assertTrue(!s.isSettled)
    }

    @Test
    fun aFailedKindFailsOnlyItsOwnSegment() = runTest {
        val failures = mutableListOf<McpTransportException>()
        val transport = McpTransportException("unreachable", null)
        val c = controller(FakeBackend(failKind = MediaType.BOOK, failure = transport), failures)
        c.updateQuery("dune")
        advanceUntilIdle()

        val s = c.state.value
        assertEquals(Segment.Failed("unreachable"), s.segments[MediaType.BOOK])
        assertIs<Segment.Loaded>(s.segments[MediaType.MOVIE])
        assertEquals(listOf(transport), failures, "a transport failure is reported to the session")
    }

    @Test
    fun aNonTransportFailureIsNotReportedToTheSession() = runTest {
        val failures = mutableListOf<McpTransportException>()
        val c = controller(FakeBackend(failKind = MediaType.MOVIE), failures)
        c.updateQuery("dune")
        advanceUntilIdle()

        assertEquals(Segment.Failed("boom"), c.state.value.segments[MediaType.MOVIE])
        assertTrue(failures.isEmpty())
    }

    @Test
    fun followedSourcesAreCachedUntilInvalidated() = runTest {
        val backend = FakeBackend()
        val c = controller(backend)
        c.updateQuery("dune")
        advanceUntilIdle()
        c.updateQuery("pod")
        advanceUntilIdle()
        assertEquals(1, backend.followedCalls, "the followed list is fetched once and reused")

        c.invalidateSources()
        c.updateQuery("dune pod")
        advanceUntilIdle()
        assertEquals(2, backend.followedCalls)
    }

    @Test
    fun unavailableFollowedSourcesFailTheirSegmentAndAreRetried() = runTest {
        val backend = FakeBackend(failFollowed = true)
        val c = controller(backend)
        c.updateQuery("dune")
        advanceUntilIdle()
        assertEquals(Segment.Failed("followed sources unavailable"), c.state.value.sources)

        backend.failFollowed = false
        c.submit()
        advanceUntilIdle()
        assertEquals(2, backend.followedCalls, "a failed fetch is not cached")
        assertIs<Segment.Loaded>(c.state.value.sources)
    }

    @Test
    fun aBlankQueryClearsResultsAndCancelsThePendingSearch() = runTest {
        val backend = FakeBackend()
        val c = controller(backend)
        c.updateQuery("dune")
        advanceUntilIdle()
        val before = backend.searches.size

        c.updateQuery("dun")
        c.updateQuery("  ")
        advanceUntilIdle()
        val s = c.state.value
        assertEquals(before, backend.searches.size, "the debounced search for \"dun\" never ran")
        assertTrue(s.isIdle)
        assertTrue(s.segments.isEmpty())
        assertEquals(Segment.Loaded(emptyList()), s.episodes)
        assertEquals(Segment.Loaded(emptyList()), s.sources)
    }

    @Test
    fun submitSearchesAtOnceAndIgnoresABlankQuery() = runTest {
        val backend = FakeBackend()
        val c = controller(backend)
        c.submit()
        advanceUntilIdle()
        assertTrue(backend.searches.isEmpty())

        c.updateQuery("dune")
        c.submit()
        runCurrent()
        assertEquals(MediaType.SEARCHABLE.size + 1, backend.searches.size, "submit skips the debounce")
        advanceUntilIdle()
        assertEquals(MediaType.SEARCHABLE.size + 1, backend.searches.size, "and cancels it, so nothing runs twice")
    }

    @Test
    fun withoutABackendTheQueryIsKeptButNothingIsSearched() = runTest {
        val c = controller(null)
        c.updateQuery("dune")
        advanceUntilIdle()
        val s = c.state.value
        assertEquals("dune", s.query)
        assertTrue(s.segments.isEmpty())
        assertEquals(Segment.Loaded(emptyList()), s.episodes)
    }

    @Test
    fun selectionWrapsAndResetsOnANewQuery() = runTest {
        val c = controller(FakeBackend())
        c.moveSelection(1)
        assertEquals(-1, c.state.value.selected, "no rows, no selection")
        assertNull(c.selectedRow())

        c.updateQuery("dune")
        advanceUntilIdle()
        val rows = c.state.value.rows
        c.moveSelection(1)
        assertEquals(0, c.state.value.selected)
        c.moveSelection(-1)
        assertEquals(rows.size - 1, c.state.value.selected, "moving up from the first row wraps to the last")
        c.moveSelection(1)
        assertEquals(0, c.state.value.selected)
        assertEquals(rows[0], c.selectedRow())

        c.setSelected(-1)
        c.moveSelection(-1)
        assertEquals(rows.size - 1, c.state.value.selected, "moving up with nothing selected wraps to the last row")

        c.updateQuery("dune 2")
        assertEquals(-1, c.state.value.selected, "a new query drops the selection")
    }

    @Test
    fun theFilterNarrowsTheSections() = runTest {
        val c = controller(FakeBackend())
        c.updateQuery("dune")
        advanceUntilIdle()
        c.setFilter(SearchFilter.MOVIES)
        val s = c.state.value
        assertEquals(listOf(MediaType.MOVIE), s.sections.map { it.type })
        assertTrue(s.rows.all { it.type == MediaType.MOVIE })
    }
}

private fun work(id: String, type: MediaType) = SearchHit(
    workId = id,
    contentType = type.apiName,
    title = "Dune $id",
    year = null,
    artworkPath = null,
    artworkHash = null,
    tvdbId = null,
    attributes = emptyMap(),
)

private fun episode(id: String) = EpisodeHit(
    id = id,
    kind = "episode",
    title = "Dune episode",
    workId = null,
    workTitle = null,
    contentType = "series",
    assetId = null,
    blobHash = null,
)
