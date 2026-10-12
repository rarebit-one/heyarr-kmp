package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.catalog.WorksPage
import one.rarebit.heyarr.mobile.library.LibraryPager
import one.rarebit.heyarr.mobile.library.Work
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

private fun work(n: Int) = Work(id = "w$n", title = "Work w$n")

/** Builds a [WorksPage] from a vararg list of works and an optional next cursor. */
private fun page(vararg works: Work, next: String? = null) =
    WorksPage(items = works.toList(), nextCursor = next)

class LibraryPagerTest {

    @Test fun firstPageLoads() {
        // A single-page library: loadNext appends items and marks the end.
        val pager = LibraryPager { page(work(1), work(2)) }
        assertTrue("first loadNext should return true", pager.loadNext())
        assertEquals("item count", 2, pager.items.size)
        assertEquals("item id", "w1", pager.items[0].id)
        assertTrue("should be at end after null cursor", pager.isAtEnd)
    }

    @Test fun pagesAccumulate() {
        // Two-page library: second loadNext appends to the first batch.
        var call = 0
        val pager = LibraryPager { cursor ->
            call++
            if (cursor == null) page(work(1), next = "p2") else page(work(2))
        }
        assertTrue("page 1 fetch", pager.loadNext())
        assertTrue("page 2 fetch", pager.loadNext())
        assertEquals("two items total", 2, pager.items.size)
        assertEquals("page 1 item", "w1", pager.items[0].id)
        assertEquals("page 2 item", "w2", pager.items[1].id)
        assertEquals("two calls made", 2, call)
        assertTrue("at end after last page", pager.isAtEnd)
    }

    @Test fun noFetchAfterEnd() {
        val pager = LibraryPager { page(work(1)) }
        pager.loadNext()
        // isAtEnd is true; subsequent calls must be no-ops.
        assertFalse("third call returns false", pager.loadNext())
        assertFalse("fourth call returns false", pager.loadNext())
        assertEquals("item count unchanged", 1, pager.items.size)
    }

    @Test fun noDuplicateInFlightFetch() {
        // The in-flight guard (AtomicBoolean) must make a concurrent second loadNext return false.
        val startFetch = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)

        val pager = LibraryPager {
            startFetch.countDown()      // signal that the first fetch started
            releaseFetch.await()        // block until the test lets it through
            page(work(1))
        }

        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<Boolean> { pager.loadNext() }

        startFetch.await()              // wait until first fetch is actually running
        val secondResult = pager.loadNext()   // must be rejected while first is in flight
        releaseFetch.countDown()        // release first fetch to complete

        val firstResult = future.get()
        pool.shutdown()

        assertTrue("first loadNext should succeed", firstResult)
        assertFalse("concurrent second loadNext must return false", secondResult)
        assertEquals("only one page's items loaded", 1, pager.items.size)
    }

    @Test fun resetDuringAFetchDropsTheStalePageAndRefetchesPageOne() {
        // A Refresh pressed while a loadMore is in flight must not splice the stale page
        // into the new list: the in-flight fetch drops it and fetches page 1 instead.
        val startFetch = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val asked = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val pager = LibraryPager { cursor ->
            asked.add(cursor)
            if (cursor == "c2") {
                startFetch.countDown()
                releaseFetch.await()
                page(work(3), next = "c3") // the stale page 2
            } else {
                page(work(1), work(2), next = "c2")
            }
        }
        assertTrue(pager.loadNext()) // page 1 loaded, cursor c2

        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<Boolean> { pager.loadNext() } // the in-flight loadMore
        startFetch.await()
        pager.reset() // Refresh, mid-fetch
        assertFalse("the Refresh's own loadNext defers to the in-flight one", pager.loadNext())
        releaseFetch.countDown()
        assertTrue("the in-flight fetch publishes the fresh page 1", future.get())
        pool.shutdown()

        assertEquals("only page 1, no stale page spliced in", listOf("w1", "w2"), pager.items.map { it.id })
        assertEquals("cursor is page 1's", "c2", pager.nextCursor)
        assertEquals("fetched page 1, stale page 2, then page 1 again", listOf(null, "c2", null), asked.toList())
    }

    @Test fun resetStartsFromPageOne() {
        var call = 0
        val pager = LibraryPager { cursor ->
            call++
            if (cursor == null) page(work(call)) else page()
        }
        pager.loadNext()                    // load page 1
        assertEquals("one item before reset", 1, pager.items.size)

        pager.reset()
        assertEquals("items cleared", 0, pager.items.size)
        assertFalse("not at end after reset", pager.isAtEnd)

        pager.loadNext()                    // load page 1 again from cursor==null
        assertEquals("one item after reset-reload", 1, pager.items.size)
        assertEquals("second fetch restarted from null cursor", 2, call)
    }

    @Test fun nextCursorReflectsFetchedPage() {
        // nextCursor should be null before any fetch, then mirror the page's cursor,
        // then become null again once the last page is fetched.
        val pager = LibraryPager { cursor ->
            if (cursor == null) page(work(1), next = "page-2") else page(work(2))
        }
        assertEquals("null before first fetch", null, pager.nextCursor)

        pager.loadNext()
        assertEquals("cursor from page 1", "page-2", pager.nextCursor)

        pager.loadNext()
        assertEquals("null after last page", null, pager.nextCursor)
        assertTrue("at end", pager.isAtEnd)
    }

    @Test fun resumeRestoresPagerState() {
        // resume() preloads items + cursor so loadNext picks up from the right page,
        // not from the beginning. Simulates composable recreation after a tab switch.
        val existing = listOf(work(1), work(2))
        var calledWithCursor: String? = "unset"
        val pager = LibraryPager { cursor ->
            calledWithCursor = cursor
            page(work(3))
        }

        pager.resume(existing, nextCursor = "mid-cursor", isAtEnd = false)

        assertEquals("items restored", existing, pager.items)
        assertEquals("nextCursor restored", "mid-cursor", pager.nextCursor)
        assertFalse("not at end", pager.isAtEnd)

        // loadNext should resume from the stored cursor, not from null.
        pager.loadNext()
        assertEquals("fetch used restored cursor", "mid-cursor", calledWithCursor)
        assertEquals("three items after resume+load", 3, pager.items.size)
    }
}
