package one.rarebit.heyarr.mobile.reader

import android.graphics.RectF
import android.os.Bundle
import android.util.SparseArray
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import one.rarebit.heyarr.mobile.R

/**
 * The PDF reader: Jetpack's [PdfViewerFragment] over a local copy of the file
 * ([PdfDownload]), replacing Readium's PDFium navigator (#162). It keeps what that
 * navigator did — continuous vertical scroll, pinch and double-tap zoom, reopening at the
 * page left — and adds what the platform renderer brings: text search, selection and
 * links. The document is parsed in the library's isolated-process service, not here.
 *
 * The page is remembered the way the Readium reader remembered it: a Locator-shaped JSON
 * in [ReadingPositionStore], on this phone only, under the asset id in [ARG_ASSET_ID] (an
 * argument, so a recreated fragment still knows where to write).
 */
class PdfReaderFragment : PdfViewerFragment() {

    /** Told when the file cannot be opened (corrupt, or password-protected and dismissed). */
    fun interface Host {
        fun onPdfFailed(reason: String)
    }

    private val assetId: String? get() = arguments?.getString(ARG_ASSET_ID)
    private var tracker: PdfPageTracker? = null
    private var pageCount = 0
    private var currentPage = -1

    // The viewer's PdfView, as handed over by onPdfViewCreated. The fragment's own
    // `pdfView` getter is library-restricted; this callback is the supported way in.
    private var view: PdfView? = null

    // The viewer's own views (the search bar, the page indicator, the fast scroller) are
    // Material Components, which need a Material theme; the app's is the framework's
    // Theme.Material, so the viewer alone inflates under Theme.Heyarr.Pdf.
    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return inflater.cloneInContext(ContextThemeWrapper(inflater.context, R.style.Theme_Heyarr_Pdf))
    }

    // ExperimentalPdfApi: androidx.pdf 1.0.0-beta01 promoted the fragment to beta but left
    // this hook experimental. It is the only supported way to the PdfView, without which
    // the page can be neither restored nor recorded; re-check it on each androidx.pdf bump.
    @OptIn(ExperimentalPdfApi::class)
    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        view = pdfView
        pdfView.addOnViewportChangedListener(
            object : PdfView.OnViewportChangedListener {
                override fun onViewportChanged(
                    firstVisiblePage: Int,
                    visiblePagesCount: Int,
                    pageLocations: SparseArray<RectF>,
                    zoomLevel: Float,
                ) {
                    // The rectangles are recycled once this returns, so read them now.
                    val bands = (0 until pageLocations.size()).map { i ->
                        val r = pageLocations.valueAt(i)
                        PdfViewport.Band(pageLocations.keyAt(i), r.top, r.bottom)
                    }
                    onViewport(PdfViewport.centrePage(bands, pdfView.height / 2f), atTop = pdfView.scrollY <= 0)
                }
            },
        )
    }

    private fun onViewport(page: Int, atTop: Boolean) {
        currentPage = page
        val step = tracker?.onViewport(page, atTop)
        android.util.Log.d("HeyarrPdfDbg", "viewport page=$page atTop=$atTop scrollY=${view?.scrollY} h=${view?.height} tracker=${tracker != null} step=$step")
        when (step) {
            is PdfPageTracker.Step.Record -> {
                val id = assetId ?: return
                PrefsReadingPositionStore(requireContext()).put(id, PdfPosition.locatorFor(step.page, pageCount))
            }

            // Posted: this runs inside the viewer's own viewport dispatch. runCatching: a
            // refused scroll (the document swapped out underneath) must not take the reader down.
            is PdfPageTracker.Step.Restore -> view?.let { v ->
                v.post {
                    val r = runCatching { v.scrollToPage(step.page) }
                    android.util.Log.d("HeyarrPdfDbg", "restore ${step.page} -> $r scrollY=${v.scrollY}")
                }
            }

            PdfPageTracker.Step.Nothing, null -> Unit
        }
    }

    override fun onDestroyView() {
        view = null
        super.onDestroyView()
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        // The toolbox's edit button hands the document to another app's annotator; a
        // file in this app's private cache is not something to pass out.
        isToolboxVisible = false
        pageCount = document.pageCount
        val stored = assetId?.let { PrefsReadingPositionStore(requireContext()).locator(it) }
        // No scroll here: this runs before the fragment hands the document to the PdfView,
        // which then has nothing laid out to scroll through. The tracker asks for the restore
        // from the first viewport changes instead.
        tracker = PdfPageTracker(PdfPosition.pageIndexOf(stored, pageCount))
        android.util.Log.d("HeyarrPdfDbg", "loaded pages=$pageCount stored=$stored asset=$assetId")
    }

    /** The loaded document's page count, 0 before it loads; for the on-device smoke test. */
    @VisibleForTesting internal val loadedPageCount: Int get() = pageCount

    /** The 0-based page under the viewport's centre, -1 before layout; for the on-device smoke test. */
    @VisibleForTesting internal fun currentPage(): Int = currentPage

    /** Scroll to 0-based [page], as the reader would; for the on-device smoke test. */
    @VisibleForTesting internal fun scrollTo(page: Int) {
        view?.scrollToPage(page)
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        (activity as? Host)?.onPdfFailed(error.message ?: error.javaClass.simpleName)
    }

    companion object {
        const val ARG_ASSET_ID = "asset_id"

        fun newInstance(assetId: String): PdfReaderFragment =
            PdfReaderFragment().apply { arguments = bundleOf(ARG_ASSET_ID to assetId) }
    }
}
