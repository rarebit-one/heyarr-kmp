package one.rarebit.heyarr.mobile.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The PDF reader's remembered page: the Locator it writes, and what it resumes from (#162). */
class PdfPositionTest {
    @Test fun locatorCarriesTheOneBasedPageWhereReaderPositionReadsIt() {
        val json = PdfPosition.locatorFor(pageIndex = 11, pageCount = 40)
        assertEquals(
            "the shape the reader stores",
            """{"type":"application/pdf","locations":""" +
                """{"position":12,"totalProgression":0.2750,"fragments":["page=12"]}}""",
            json,
        )
        assertEquals("ReaderPosition reads the page back", 12, ReaderPosition.pageOf(json))
    }

    @Test fun roundTripsEveryPage() {
        for (i in 0 until 5) {
            assertEquals("page $i", i, PdfPosition.pageIndexOf(PdfPosition.locatorFor(i, 5), 5))
        }
    }

    @Test fun resumesFromALocatorTheReadiumPdfNavigatorSaved() {
        // A position kept before #162, written by Readium's PDFium navigator.
        val readium = """{"href":"publication.pdf","type":"application/pdf","title":"Ch. 2",""" +
            """"locations":{"fragments":["page=7"],"progression":0.0,"position":7,"totalProgression":0.15}}"""
        assertEquals(6, PdfPosition.pageIndexOf(readium, 40))
    }

    @Test fun nothingStoredOrUnreadableOpensAtTheStart() {
        assertEquals(0, PdfPosition.pageIndexOf(null, 10))
        assertEquals(0, PdfPosition.pageIndexOf("", 10))
        assertEquals(0, PdfPosition.pageIndexOf("""{"href":"/ch3.xhtml","locations":{"progression":0.4}}""", 10))
    }

    @Test fun aStoredPagePastTheEndIsClampedToTheLastPage() {
        val past = PdfPosition.locatorFor(30, 40)
        assertEquals("a shorter file under the same asset", 9, PdfPosition.pageIndexOf(past, 10))
        assertEquals("an empty document", 0, PdfPosition.pageIndexOf(PdfPosition.locatorFor(3, 10), 0))
    }

    @Test fun progressionIsLocaleIndependent() {
        val default = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY) // decimal comma
            assertEquals(
                """{"type":"application/pdf","locations":""" +
                    """{"position":2,"totalProgression":0.5000,"fragments":["page=2"]}}""",
                PdfPosition.locatorFor(1, 2),
            )
        } finally {
            java.util.Locale.setDefault(default)
        }
    }

    @Test fun trackerIgnoresTheFirstLayoutUntilTheRestoreLands() {
        val t = PdfPageTracker(restoreTo = 6)
        assertNull("the layout at page 0 must not overwrite page 7", t.onViewport(0))
        assertNull(t.onViewport(0))
        assertEquals("the restore landed", 6, t.onViewport(6))
        assertNull("same page again", t.onViewport(6))
        assertEquals(7, t.onViewport(7))
        assertEquals("back to the start is a real move now", 0, t.onViewport(0))
    }

    @Test fun trackerStopsWaitingWhenTheReaderScrollsFirst() {
        val t = PdfPageTracker(restoreTo = 6)
        assertEquals(2, t.onViewport(2))
    }

    @Test fun trackerWithNothingToRestoreRecordsFromTheStart() {
        val t = PdfPageTracker(restoreTo = 0)
        assertEquals(0, t.onViewport(0))
        assertNull(t.onViewport(0))
        assertNull("no page", t.onViewport(-1))
    }
}
