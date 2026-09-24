package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.library.Variants
import one.rarebit.heyarr.desktop.library.Work
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Download-folder series works ("Yellowstone Season 4 Mp4") fold under the real series (heyarr-core#470). */
class VariantsTest {
    private val works = listOf(
        Work(id = "ys", title = "Yellowstone", kind = "series", year = 2018),
        Work(id = "ys4", title = "Yellowstone Season 4 Mp4 1080p", kind = "series"),
        Work(id = "ys5", title = "Yellowstone Season 5 Mp4", kind = "series"),
        Work(id = "ysm", title = "Yellowstone", kind = "movie", year = 2018),
        Work(id = "sev", title = "Severance", kind = "series"),
        Work(id = "s2", title = "Season 2", kind = "series"),
    )

    @Test fun variantsFoldUnderTheCanonicalSeriesOfTheSameKind() {
        val g = Variants.group(works)
        assertEquals(listOf("ys4", "ys5"), g["ys"]?.map { it.id })
        assertEquals(setOf("ys4", "ys5"), Variants.variantIds(works))
        assertNull(g["ysm"], "a movie of the same name is not a home for series variants")
        assertEquals(4, Variants.seasonOf(works[1]))
    }

    @Test fun aSeasonTitleWithNoCanonicalWorkStaysVisible() {
        assertTrue(Variants.variantIds(listOf(Work(id = "x", title = "Foo Season 1", kind = "series"))).isEmpty())
        assertNull(Variants.split("Season 2"))
        assertEquals(Variants.Split("the expanse", 3), Variants.split("The Expanse S3 2160p"))
    }
}
