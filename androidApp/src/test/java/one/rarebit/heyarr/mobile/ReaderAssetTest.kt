package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.mobile.reader.ReaderFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderAssetTest {
    @Test fun mimeWinsThenFilename() {
        assertEquals(ReaderFormat.EPUB, ReaderFormat.of("application/epub+zip", "x.bin"))
        assertEquals(ReaderFormat.PDF, ReaderFormat.of(null, "paper.PDF"))
        assertEquals(ReaderFormat.CBZ, ReaderFormat.of("application/octet-stream", "issue1.cbz"))
        assertEquals(ReaderFormat.CBR, ReaderFormat.of("application/x-cbr", null))
        assertEquals(ReaderFormat.AUDIOBOOK, ReaderFormat.of("audio/mp4", "book.m4b"))
        assertEquals(ReaderFormat.AUDIOBOOK, ReaderFormat.of(null, "book.m4b"))
        assertNull(ReaderFormat.of("video/mp4", "film.mp4"))
        assertNull(ReaderFormat.of(null, null))
    }

    @Test fun theIntentExtraNamesAFormat() {
        assertEquals("a PDF opens the PDF viewer", ReaderFormat.PDF, ReaderFormat.fromExtra("PDF"))
        assertEquals(ReaderFormat.EPUB, ReaderFormat.fromExtra(ReaderFormat.EPUB.name))
        assertNull("no extra falls back to Readium", ReaderFormat.fromExtra(null))
        assertNull(ReaderFormat.fromExtra("pdf"))
    }
}
