package one.rarebit.heyarr.mobile

import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.DisplayCutout
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.children
import androidx.pdf.view.PdfView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.reader.PdfCache
import one.rarebit.heyarr.mobile.reader.PdfReaderFragment
import one.rarebit.heyarr.mobile.reader.PrefsReadingPositionStore
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The PDF reader's search button never covers the viewer's scroll thumb or its page chip
 * (#223). On a phone the button sat top right, on the thumb and on "11/439". The viewer draws
 * both against the right edge and moves them down it with the scroll, so this checks them at
 * the first page and at the last, in portrait and landscape, under three cutouts: none, the
 * emulator's own hole punch and an emulated tall notch. The button also has to stay inside the
 * reader's system-bar and cutout padding (#222) and off the cutout itself.
 *
 * Each orientation is frozen before the reader opens, so no rotation recreates it mid-check.
 * The PDF is seeded into the reader's download cache, so ReaderActivity opens it without a
 * node; the URL's port has nothing listening, so a cache miss fails rather than hangs.
 */
@RunWith(AndroidJUnit4::class)
class PdfReaderSearchButtonTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val url = "http://127.0.0.1:9/api/v1/blobs/blake3:${"2".repeat(64)}/content"

    // The emulator's own cutout overlay(s), put back afterwards.
    private var cutoutsBefore: List<String> = emptyList()

    /** The cutout a check runs under. */
    private enum class Cutout { NONE, DEVICE, TALL }

    @Before fun seedTheCache() {
        // ReaderActivity's PDF_CACHE_DIR; PdfDownload names the file after the blob hash.
        val file = File(ctx.cacheDir, "reader-pdf/${PdfCache.fileNameFor(url)}")
        file.parentFile?.mkdirs()
        val doc = PdfDocument()
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = TEXT_SIZE
        }
        repeat(PAGES) { i ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, i + 1).create())
            page.canvas.drawText("Page ${i + 1}", MARGIN, MARGIN, paint)
            doc.finishPage(page)
        }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        cutoutsBefore = cutoutOverlays().filter { it.second }.map { it.first }
    }

    @After fun restoreTheDevice() {
        cutoutOverlays().filter { it.second }.forEach { shell("cmd overlay disable ${it.first}") }
        cutoutsBefore.forEach { shell("cmd overlay enable $it") }
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test fun theSearchButtonClearsTheThumbAndPageChipWithNoCutout() = checkBothOrientations(Cutout.NONE)

    @Test fun theSearchButtonClearsTheThumbPageChipAndTheHolePunch() = checkBothOrientations(Cutout.DEVICE)

    @Test fun theSearchButtonClearsTheThumbPageChipAndATallCutout() = checkBothOrientations(Cutout.TALL)

    private fun checkBothOrientations(cutout: Cutout) {
        useCutout(if (cutout == Cutout.TALL) TALL_CUTOUT else null)
        checkIn(cutout, "portrait", UiAutomation.ROTATION_FREEZE_0, Configuration.ORIENTATION_PORTRAIT)
        checkIn(cutout, "landscape", UiAutomation.ROTATION_FREEZE_90, Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun checkIn(cutout: Cutout, orientation: String, rotation: Int, expected: Int) {
        assertTrue("could not freeze the rotation for $orientation", instrumentation.uiAutomation.setRotation(rotation))
        // No remembered page: the reader would restore it over the scrolls below.
        PrefsReadingPositionStore(ctx).put(ASSET, "{}")
        val intent = ReaderActivity.intent(
            ctx,
            assetId = ASSET,
            url = url,
            title = "Search button",
            format = ReaderFormat.PDF,
        )
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            val where = "$orientation, ${cutout.name.lowercase()} cutout"
            scenario.await(
                "the $where PDF to load",
                seen = { "orientation=${it.resources.configuration.orientation} cutout=${it.displayCutout()}" },
            ) { activity ->
                // Applied here, not once: a late cutout-overlay change recreates the activity.
                activity.useCutoutMode(cutout)
                activity.resources.configuration.orientation == expected && activity.hasCutout(cutout) &&
                    activity.pdfView() != null && activity.reader()?.loadedPageCount == PAGES &&
                    activity.searchButton()?.isLaidOut == true
            }
            // The thumb and chip fade out between scrolls; keep them drawn so their bounds are current.
            scenario.onActivity { it.pdfView()?.fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_SHOW }
            scenario.checkAt("$where, first page", page = 0) { pdf, thumb -> thumb.centerY() < pdf.height / 2 }
            scenario.checkAt("$where, last page", page = PAGES - 1) { pdf, thumb ->
                !pdf.canScrollVertically(1) && thumb.centerY() > pdf.height / 2
            }
        }
    }

    /**
     * Lay the window out as the phone would. Android 15+ draws a targetSdk 35 app into the cutout
     * on every edge (ALWAYS), while this API 34 emulator would otherwise letterbox a landscape
     * window away from it. The emulator's hole punch is part of its device profile, not an
     * emulation overlay, so it cannot be switched off; a window that NEVER enters the cutout gets
     * no cutout inset, the layout of a phone without one.
     */
    private fun ReaderActivity.useCutoutMode(cutout: Cutout) {
        val mode = if (cutout == Cutout.NONE) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        if (window.attributes.layoutInDisplayCutoutMode != mode) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = mode }
        }
    }

    private fun ReaderActivity.displayCutout(): DisplayCutout? = window.decorView.rootWindowInsets?.displayCutout

    /** Whether the window reports the [cutout] asked for; the tall overlay applies asynchronously. */
    private fun ReaderActivity.hasCutout(cutout: Cutout): Boolean {
        val rects = displayCutout()?.boundingRects.orEmpty().filterNot { it.isEmpty }
        val longest = rects.maxOfOrNull { maxOf(it.width(), it.height()) } ?: 0
        return when (cutout) {
            Cutout.NONE -> rects.isEmpty()
            Cutout.DEVICE -> rects.isNotEmpty()
            Cutout.TALL -> longest > TALL_MIN_DP * resources.displayMetrics.density
        }
    }

    /** Scroll to [page], wait until the thumb is drawn where [settled] says, then check the button. */
    private fun ActivityScenario<ReaderActivity>.checkAt(
        where: String,
        page: Int,
        settled: (PdfView, Rect) -> Boolean,
    ) {
        await(
            "the thumb to be drawn at the $where",
            seen = { a ->
                a.pdfView()?.let {
                    "scrollY=${it.scrollY} height=${it.height} thumb=${it.drawnBounds(THUMB_BOUNDS)}"
                }.orEmpty()
            },
        ) { activity ->
            val pdf = activity.pdfView() ?: return@await false
            // Re-issued each poll: once a document lays out, the reader restores the page it
            // remembers (after the rotation, the last page, recorded in portrait) over a single
            // earlier scroll, and a scroll sent before that layout goes nowhere.
            activity.reader()?.scrollTo(page)
            pdf.invalidate()
            val thumb = pdf.drawnBounds(THUMB_BOUNDS)
            !thumb.isEmpty && settled(pdf, thumb)
        }
        onActivity { activity ->
            val pdf = checkNotNull(activity.pdfView())
            val button = checkNotNull(activity.searchButton()).windowRect()
            val thumb = pdf.drawnWindowRect(THUMB_BOUNDS)
            val chip = pdf.drawnWindowRect(CHIP_BOUNDS)
            assertFalse("the page chip is drawn ($where)", chip.isEmpty)
            assertFalse(
                "the search button $button covers the scroll thumb $thumb ($where)",
                Rect.intersects(button, thumb),
            )
            assertFalse("the search button $button covers the page chip $chip ($where)", Rect.intersects(button, chip))

            val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val padded = root.windowRect().apply {
                left += root.paddingLeft
                top += root.paddingTop
                right -= root.paddingRight
                bottom -= root.paddingBottom
            }
            assertTrue(
                "the search button $button is outside the system-bar padding $padded ($where)",
                padded.contains(button),
            )
            val cutouts = root.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
            cutouts.forEach { cutout ->
                assertFalse(
                    "the search button $button covers the cutout $cutout ($where)",
                    Rect.intersects(button, cutout),
                )
            }
        }
    }

    private fun ReaderActivity.reader(): PdfReaderFragment? =
        supportFragmentManager.fragments.filterIsInstance<PdfReaderFragment>().firstOrNull { it.isResumed }

    private fun ReaderActivity.pdfView(): PdfView? = reader()?.let { findPdfView(it.requireView()) }

    // The reader's container holds the viewer's view and, once the PDF is shown, the button.
    private fun ReaderActivity.searchButton(): ComposeView? =
        findViewById<ViewGroup>(R.id.reader_container).children.filterIsInstance<ComposeView>().firstOrNull()

    private fun findPdfView(view: View): PdfView? = when (view) {
        is PdfView -> view
        is ViewGroup -> view.children.firstNotNullOfOrNull(::findPdfView)
        else -> null
    }

    private fun View.windowRect(): Rect {
        val at = IntArray(2).also(::getLocationInWindow)
        return Rect(at[0], at[1], at[0] + width, at[1] + height)
    }

    // The viewer draws the thumb and chip on its own canvas, so they are not views. Their last
    // drawn bounds are androidx.pdf internals (Kotlin `internal`, hence the mangled getter
    // names), read by reflection; re-check the names on each androidx.pdf bump, since a rename
    // fails here. They are in the PdfView's coordinates, shifted down by its top padding
    // (PdfView.drawFastScroller translates the canvas by the scroll and paddingTop).
    private fun PdfView.drawnBounds(getter: String): Rect {
        val scroller = checkNotNull(fastScroller) { "the viewer has no fast scroller" }
        return Rect(scroller.javaClass.getMethod(getter).invoke(scroller) as Rect)
    }

    private fun PdfView.drawnWindowRect(getter: String): Rect {
        val at = IntArray(2).also(::getLocationInWindow)
        return drawnBounds(getter).apply { offset(at[0], at[1] + paddingTop) }
    }

    /** Wait (on the test thread) until [check], read on the main thread, holds. */
    private fun ActivityScenario<ReaderActivity>.await(
        what: String,
        seen: (ReaderActivity) -> String = { "" },
        check: (ReaderActivity) -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var last = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            onActivity {
                ok = check(it)
                last = seen(it)
            }
            if (ok) return
            SystemClock.sleep(POLL_MS)
        }
        fail("timed out waiting for $what ($last)")
    }

    /** Show only [overlay]'s emulated cutout, or none of them for null. */
    private fun useCutout(overlay: String?) {
        val overlays = cutoutOverlays()
        overlays.filter { it.second && it.first != overlay }.forEach { shell("cmd overlay disable ${it.first}") }
        if (overlay != null) {
            assertTrue("this emulator has no $overlay overlay ($overlays)", overlays.any { it.first == overlay })
            shell("cmd overlay enable $overlay")
        }
    }

    /** The emulator's cutout-emulation overlays and whether each is enabled (`[x]`). */
    private fun cutoutOverlays(): List<Pair<String, Boolean>> = shell("cmd overlay list android").lines()
        .map { it.trim() }
        .filter { CUTOUT_OVERLAY_PREFIX in it }
        .map { it.substringAfterLast(' ') to it.startsWith("[x]") }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }

    private companion object {
        const val ASSET = "search-button-asset"
        const val PAGES = 40 // tall enough to scroll, so the fast scroller draws, in landscape too
        const val PAGE_WIDTH = 612 // US Letter, in points
        const val PAGE_HEIGHT = 792
        const val MARGIN = 72f
        const val TEXT_SIZE = 36f
        const val THUMB_BOUNDS = "getThumbScreenBounds\$pdf_viewer"
        const val CHIP_BOUNDS = "getIndicatorScreenBounds\$pdf_viewer"
        const val CUTOUT_OVERLAY_PREFIX = "com.android.internal.display.cutout.emulation."
        const val TALL_CUTOUT = CUTOUT_OVERLAY_PREFIX + "tall"
        const val TALL_MIN_DP = 64 // the tall notch is 96 dp wide; the hole punch about 45
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
