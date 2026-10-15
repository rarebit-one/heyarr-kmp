package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.ui.Experience
import one.rarebit.heyarr.desktop.ui.screens.LibraryContents
import one.rarebit.heyarr.desktop.ui.screens.LibraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogStateTest {
    @Test
    fun aFullLibraryResponseServesEveryShelfAndNodeChangeClearsIt() {
        val contents = LibraryContents()
        val watch = LibraryState(contents, Experience.WATCH)
        val listen = LibraryState(contents, Experience.LISTEN)
        val manage = LibraryState(contents)
        watch.resetFor(1)
        listen.target.works = listOf(Work("music", "Album", "music"))
        assertEquals("music", listen.works?.single()?.id)
        assertNull(watch.works)

        manage.target.works = listOf(Work("movie", "Film", "movie"))
        assertEquals("movie", watch.works?.single()?.id)
        assertEquals("movie", listen.works?.single()?.id)

        watch.resetFor(2)
        assertNull(manage.works)
        assertNull(listen.works)
    }
}
