package one.rarebit.heyarr.mobile.reader

import java.util.Locale

/**
 * A PDF's place, kept in the same per-asset slot ([ReadingPositionStore]) and the same
 * Locator shape the Readium reader used for PDFs, so a position saved before #162 still
 * resumes: Readium's PDF locators carry the 1-based page as `locations.position`, which
 * is what [ReaderPosition.pageOf] reads. Pure, unit-tested.
 */
object PdfPosition {
    private const val PDF_TYPE = "application/pdf"

    /** The 0-based page to open [pageCount] pages at, from a stored locator; 0 when there is none. */
    fun pageIndexOf(locatorJson: String?, pageCount: Int): Int {
        val page = locatorJson?.let { ReaderPosition.pageOf(it) } ?: 1
        return if (pageCount <= 0) 0 else (page - 1).coerceIn(0, pageCount - 1)
    }

    /**
     * The locator for 0-based page [pageIndex] of [pageCount]. No `href`: a PDF is never
     * handed back to Readium, so only `locations` is read.
     */
    fun locatorFor(pageIndex: Int, pageCount: Int): String {
        val page = pageIndex.coerceAtLeast(0) + 1
        val progression = if (pageCount > 0) pageIndex.coerceIn(0, pageCount - 1).toDouble() / pageCount else 0.0
        val total = String.format(Locale.ROOT, "%.4f", progression)
        return """{"type":"$PDF_TYPE","locations":{"position":$page,"totalProgression":$total,""" +
            """"fragments":["page=$page"]}}"""
    }
}

/**
 * Decides which viewport changes are a page worth recording. The viewer lays the document
 * out at page 0 before the restore scroll lands, and saving that would overwrite the very
 * position being restored; so while a restore to a later page is pending, page-0 reports
 * are ignored. Any other page ends the wait — the restore landed, or the reader scrolled
 * there first. Pure, unit-tested.
 */
class PdfPageTracker(restoreTo: Int) {
    private var awaitingRestore = restoreTo > 0
    private var last = -1

    /** The 0-based page to record for a viewport whose first visible page is [first], or null. */
    fun onViewport(first: Int): Int? {
        if (awaitingRestore && first > 0) awaitingRestore = false
        val record = first >= 0 && !awaitingRestore && first != last
        if (record) last = first
        return first.takeIf { record }
    }
}
