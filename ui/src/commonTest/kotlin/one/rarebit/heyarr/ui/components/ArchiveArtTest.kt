package one.rarebit.heyarr.ui.components

import one.rarebit.heyarr.ui.theme.CardAspect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A work keeps the same stand-in art across runs, and a shelf of works does not all share one. */
class ArchiveArtTest {
    @Test
    fun theSameSeedAlwaysPicksTheSameArt() {
        assertEquals(
            ArchiveArt.fallbackCover(CardAspect.POSTER, "w-piranesi"),
            ArchiveArt.fallbackCover(CardAspect.POSTER, "w-piranesi"),
        )
        assertEquals(ArchiveArt.postcard("home"), ArchiveArt.postcard("home"))
    }

    @Test
    fun aShelfOfWorksSpreadsAcrossTheCovers() {
        val picks = (1..40).map { ArchiveArt.fallbackCover(CardAspect.WIDE, "work-$it") }.toSet()
        assertTrue(picks.size > 1, "40 works all picked the same cover")
    }
}
