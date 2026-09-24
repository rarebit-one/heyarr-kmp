package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.heyarr.DesiredItemJson
import one.rarebit.heyarr.core.state.LibraryIndex
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.testfixtures.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals

/** In library / Wanted / Missing / Not tracked derive from want state only — never from history. */
class LibraryStatusTest {

    @Test
    fun stateWordsMapToStatuses() {
        assertEquals(LibraryStatus.IN_LIBRARY, LibraryStatus.ofState("FULLY_SATISFIED"))
        assertEquals(LibraryStatus.IN_LIBRARY, LibraryStatus.ofState("available"))
        assertEquals(LibraryStatus.MISSING, LibraryStatus.ofState("MISSING"))
        assertEquals(LibraryStatus.WANTED, LibraryStatus.ofState("SELECTED"))
        // An unknown word is treated as in progress, never as satisfied.
        assertEquals(LibraryStatus.WANTED, LibraryStatus.ofState("SOMETHING_NEW"))
    }

    @Test
    fun theBestSatisfiedWantWins() {
        assertEquals(LibraryStatus.NOT_TRACKED, LibraryStatus.combine(emptyList()))
        assertEquals(LibraryStatus.IN_LIBRARY, LibraryStatus.combine(listOf(LibraryStatus.MISSING, LibraryStatus.IN_LIBRARY)))
        assertEquals(LibraryStatus.WANTED, LibraryStatus.combine(listOf(LibraryStatus.MISSING, LibraryStatus.WANTED)))
        assertEquals(LibraryStatus.MISSING, LibraryStatus.combine(listOf(LibraryStatus.MISSING)))
    }

    @Test
    fun indexAnswersPerWorkAndSupportsOptimisticWants() {
        val index = LibraryIndex(DesiredItemJson.list(Fixtures.desired))
        assertEquals(LibraryStatus.MISSING, index.statusOf(Fixtures.SINTEL))
        assertEquals(LibraryStatus.WANTED, index.statusOf("w-piranesi"))
        assertEquals(LibraryStatus.IN_LIBRARY, index.statusOf(Fixtures.YELLOWSTONE))
        assertEquals(LibraryStatus.IN_LIBRARY, index.statusOf("w-dune"))
        assertEquals(LibraryStatus.NOT_TRACKED, index.statusOf("w-blue"))
        val optimistic = index.withPendingWant("w-blue", "qp-everyday")
        assertEquals(LibraryStatus.WANTED, optimistic.statusOf("w-blue"))
        // The original is untouched — that is what rollback restores.
        assertEquals(LibraryStatus.NOT_TRACKED, index.statusOf("w-blue"))
    }
}
