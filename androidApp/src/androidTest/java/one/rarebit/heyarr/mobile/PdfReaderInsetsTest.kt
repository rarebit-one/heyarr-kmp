package one.rarebit.heyarr.mobile

import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PDF reader stays clear of the status bar, the navigation bar and the display cutout,
 * in portrait and in landscape. On a phone, Jetpack's PDF viewer drew its first page and
 * the search button under the clock, because nothing applied the edge-to-edge insets. The
 * download here fails fast (nothing listens on the port); the layout under test is the
 * reader's own, which is there before any file arrives.
 */
@RunWith(AndroidJUnit4::class)
class PdfReaderInsetsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()

    @Test fun thePdfReaderIsPaddedClearOfTheSystemBars() {
        val intent = ReaderActivity.intent(
            ctx,
            assetId = "insets-asset",
            url = "http://127.0.0.1:9/api/v1/blobs/blake3:${"0".repeat(64)}/content",
            title = "Insets",
            format = ReaderFormat.PDF,
        )
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            scenario.awaitPadded("portrait")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            scenario.awaitPadded("landscape")
        }
    }

    /** Wait until the reader's root is padded by exactly the window's bar and cutout insets. */
    private fun ActivityScenario<ReaderActivity>.awaitPadded(orientation: String) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        var seen = ""
        while (SystemClock.uptimeMillis() < deadline) {
            var ok = false
            onActivity { activity ->
                val root: View = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(types)
                val padding = listOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
                seen = "insets=$insets padding=$padding"
                ok = insets != null && insets.top > 0 &&
                    padding == listOf(insets.left, insets.top, insets.right, insets.bottom)
            }
            if (ok) return
            SystemClock.sleep(POLL_MS)
        }
        fail("the PDF reader is not padded clear of the system bars in $orientation ($seen)")
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
