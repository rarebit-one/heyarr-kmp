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
 * The page is the one under the viewport's centre ([PdfViewport.centrePage]), because that
 * is where the viewer's scrollToPage puts a page. Recording the first visible page instead
 * would drift one page back on every reopen.
 *
 * A restore can only scroll once the viewer has the document laid out: the fragment's
 * load callback runs before the document even reaches the view. So the restore is asked
 * for from the viewport changes themselves. While the view still sits at the top, each
 * change asks again (at most [MAX_RESTORE_ATTEMPTS] times) and is never recorded, because
 * recording it would overwrite the very position being restored. The wait ends when the
 * view leaves the top (the restore landed, or the reader scrolled first) or when the
 * attempts run out. In the last case the reader stays at the start, and the start is
 * recorded only after a move away and back.
 */
class PdfPageTracker(private val restoreTo: Int) {
    /** What the reader does with one viewport change. */
    sealed interface Step {
        /** Save this 0-based page. */
        data class Record(val page: Int) : Step

        /** Scroll to this 0-based page (again): the restore has not landed yet. */
        data class Restore(val page: Int) : Step

        data object Nothing : Step
    }

    private var awaitingRestore = restoreTo > 0
    private var attempts = 0
    private var last = -1

    /** The step for a viewport centred on [page] (-1 for none), [atTop] while not yet scrolled. */
    fun onViewport(page: Int, atTop: Boolean): Step {
        if (awaitingRestore) {
            if (atTop && attempts < MAX_RESTORE_ATTEMPTS) {
                attempts++
                return Step.Restore(restoreTo)
            }
            awaitingRestore = false
            if (atTop) last = page // gave up: staying at the start is not a move
        }
        val record = page >= 0 && page != last
        if (record) last = page
        return if (record) Step.Record(page) else Step.Nothing
    }

    companion object {
        const val MAX_RESTORE_ATTEMPTS = 5
    }
}

/** Where the viewport is, from the page rectangles the viewer reports. Pure, unit-tested. */
object PdfViewport {
    /** One laid-out page's vertical extent, in view coordinates. */
    data class Band(val page: Int, val top: Float, val bottom: Float)

    /**
     * The page under [centreY], or the nearest page when [centreY] falls between two
     * (the gap between pages); -1 when nothing is laid out.
     */
    fun centrePage(bands: List<Band>, centreY: Float): Int {
        val hit = bands.firstOrNull { centreY >= it.top && centreY <= it.bottom }
        return (
            hit
                ?: bands.minByOrNull { minOf(kotlin.math.abs(it.top - centreY), kotlin.math.abs(it.bottom - centreY)) }
            )
            ?.page ?: -1
    }
}
