package one.rarebit.heyarr.mobile

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.fragment.app.testing.FragmentScenario
import androidx.fragment.app.testing.launchFragmentInContainer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.reader.PdfPosition
import one.rarebit.heyarr.mobile.reader.PdfReaderFragment
import one.rarebit.heyarr.mobile.reader.PrefsReadingPositionStore
import one.rarebit.heyarr.mobile.reader.ReaderPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opens a real PDF in the reader's PDF viewer on a device (#162). The JVM tests pin the
 * position rules; only a device can show that Jetpack's viewer loads a file:// document
 * through its isolated-process service, that its Material views inflate under the app's
 * framework theme (the fragment re-themes them), and that the remembered page is restored
 * rather than overwritten by the first layout at page 0.
 */
@RunWith(AndroidJUnit4::class)
class PdfReaderSmokeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pdf = File(ctx.cacheDir, "smoke/reader.pdf")

    @Before fun writeAPdf() {
        pdf.parentFile?.mkdirs()
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
        pdf.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    private fun open(): FragmentScenario<PdfReaderFragment> {
        val scenario = launchFragmentInContainer(themeResId = R.style.Theme_Heyarr) {
            PdfReaderFragment.newInstance(ASSET)
        }
        scenario.onFragment { it.documentUri = Uri.fromFile(pdf) }
        return scenario
    }

    /** Wait (on the test thread) until [check], read on the main thread, holds. */
    private fun FragmentScenario<PdfReaderFragment>.awaitFragment(what: String, check: (PdfReaderFragment) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var seen = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            onFragment {
                ok = check(it)
                seen = "pages=${it.loadedPageCount} firstVisible=${it.firstVisiblePage()} stored=${storedPage()}"
            }
            if (ok) return
            SystemClock.sleep(POLL_MS)
        }
        fail("timed out waiting for $what ($seen)")
    }

    private fun storedPage(): Int? = PrefsReadingPositionStore(ctx).locator(ASSET)?.let { ReaderPosition.pageOf(it) }

    @Test fun aPdfOpensAtTheRememberedPageAndRecordsTheNext() {
        PrefsReadingPositionStore(ctx).put(ASSET, PdfPosition.locatorFor(pageIndex = 3, pageCount = PAGES))
        val scenario = open()
        scenario.awaitFragment("the document to load") { it.loadedPageCount == PAGES }
        scenario.awaitFragment("the restore to page 4") { it.firstVisiblePage() == 3 }
        assertEquals("the first layout did not overwrite the stored page", 4, storedPage())

        scenario.onFragment { it.scrollTo(1) }
        scenario.awaitFragment("the scroll to page 2") { it.firstVisiblePage() == 1 }
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (storedPage() != 2 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertEquals("the new page is remembered", 2, storedPage())
    }

    @Test fun aPdfWithNoRememberedPageOpensAtTheStart() {
        PrefsReadingPositionStore(ctx).put(ASSET, "{}")
        val scenario = open()
        scenario.awaitFragment("the document to load") { it.loadedPageCount == PAGES }
        scenario.onFragment { assertEquals(0, it.firstVisiblePage()) }
    }

    private companion object {
        const val ASSET = "smoke-pdf-asset"
        const val PAGES = 10
        const val PAGE_WIDTH = 612 // US Letter, in points
        const val PAGE_HEIGHT = 792
        const val MARGIN = 72f
        const val TEXT_SIZE = 36f
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
