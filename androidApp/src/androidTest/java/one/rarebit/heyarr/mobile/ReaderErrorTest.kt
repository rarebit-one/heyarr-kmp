package one.rarebit.heyarr.mobile

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.reader.PrefsReadingPositionStore
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import one.rarebit.heyarr.mobile.reader.ReaderProblem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book that will not open says why in words, clear of the system bars, with Retry and Back
 * (heyarr-kmp#240). On a phone the restored reader was refused the blob and printed Readium's
 * error object ("…AssetRetriever\$RetrieveUrlError\$Reading@6836f4e") under the clock, with no
 * way on but Back. Here the node refuses (401, as it refuses a request with no credential),
 * the reader says so, and Retry opens the book at its page once the node lets it.
 */
@RunWith(AndroidJUnit4::class)
class ReaderErrorTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val positions = PrefsReadingPositionStore(ctx)
    private lateinit var server: ClipServer

    @Before fun serveABook() {
        server = ClipServer(TestEpub.bytes(), contentType = "application/epub+zip", name = "book.epub")
        positions.put(ASSET, TestEpub.CHAPTER_TWO)
    }

    @After fun tearDown() = server.close()

    @Test fun aRefusedBookSaysSoClearOfTheBarsAndRetryOpensIt() {
        server.refusing = true
        val intent = ReaderActivity.intent(ctx, ASSET, server.url, "Book", ReaderFormat.EPUB)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            // Laid out as Android 15 lays out a targetSdk 35 app, edge to edge, on the API 34
            // emulator too: the bars' insets reach the reader instead of the window fitting them.
            scenario.onActivity { WindowCompat.setDecorFitsSystemWindows(it.window, false) }
            val title = ReaderProblem.REFUSED.title
            compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(ReaderProblem.REFUSED.detail).assertExists()
            compose.onNodeWithText("Back").assertExists()
            // Nothing of the error object reaches the screen.
            for (code in listOf("org.readium", "RetrieveUrlError", "Exception", "@")) {
                compose.onAllNodesWithText(code, substring = true).assertCountEquals(0)
            }

            var bars = 0
            scenario.onActivity { activity ->
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.let {
                    bars =
                        it.getInsets(
                            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                        ).top
                }
            }
            compose.waitForIdle()
            val top = compose.onNodeWithText(title).fetchSemanticsNode().boundsInWindow.top
            assertTrue("the message starts below the status bar (top $top, bar $bars)", bars > 0 && top >= bars)

            server.refusing = false
            compose.onNodeWithText("Retry").performClick()
            TestEpub.awaitOpenedInChapterTwo(positions, ASSET, "chapter two, after Retry")
        }
    }

    private companion object {
        const val ASSET = "reader-error-asset"
        const val TIMEOUT_MS = 30_000L
    }
}
