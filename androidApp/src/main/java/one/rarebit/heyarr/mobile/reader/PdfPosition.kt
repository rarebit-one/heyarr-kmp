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
 * Decides, for each viewport change, whether to record the page or retry the restore. Pure,
 * unit-tested.
 *
 * The viewer lays the document out at page 0 first, and a scroll asked for as the document
 * loads does not always land, because the layout it scrolls through does not exist yet. So
 * while a restore to a later page is pending, a viewport still at page 0 asks for the
 * restore again (at most [MAX_RESTORE_ATTEMPTS] times) and is never recorded, because
 * recording it would overwrite the very position being restored. The wait ends when the
 * target page shows, when the reader scrolls somewhere else first, or when the attempts
 * run out. In the last case the reader stays at the start and page 0 is recorded only
 * after a move away and back.
 */
class PdfPageTracker(private val restoreTo: Int) {
    /** What the reader does with one viewport change. */
    sealed interface Step {
        /** Save this 0-based page. */
        data class Record(val page: Int) : Step

        /** Scroll to this 0-based page again: the restore has not landed yet. */
        data class Restore(val page: Int) : Step

        data object Nothing : Step
    }

    private var awaitingRestore = restoreTo > 0
    private var attempts = 0
    private var last = -1

    /** The step for a viewport whose first visible page is [first] (-1 for none). */
    fun onViewport(first: Int): Step {
        if (awaitingRestore) {
            if (first == 0 && attempts < MAX_RESTORE_ATTEMPTS) {
                attempts++
                return Step.Restore(restoreTo)
            }
            awaitingRestore = false
            if (first == 0) last = 0 // gave up: staying at the start is not a move
        }
        val record = first >= 0 && first != last
        if (record) last = first
        return if (record) Step.Record(first) else Step.Nothing
    }

    companion object {
        const val MAX_RESTORE_ATTEMPTS = 5
    }
}
