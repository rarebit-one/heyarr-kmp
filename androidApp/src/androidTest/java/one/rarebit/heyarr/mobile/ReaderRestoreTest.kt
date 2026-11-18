package one.rarebit.heyarr.mobile

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.reader.PrefsReadingPositionStore
import one.rarebit.heyarr.mobile.reader.ReaderActivity
import one.rarebit.heyarr.mobile.reader.ReaderFormat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reader brought back from its saved state (heyarr-kmp#232): what Android does after a
 * rotation, and after the process died behind other apps and the person returns to the book.
 * A small EPUB, built here and served over loopback as the blob route serves it, opens in the
 * real [ReaderActivity] at the page this phone kept; the activity is recreated from its saved
 * state; it opens again, at the same page, rather than failing to put Readium's navigator back.
 *
 * What it cannot show: the process itself dying, which ends an instrumented test. Here the
 * process lives on, so the reader opens in place; after a real death it hands the book to the
 * shell instead (heyarr-kmp#240), which reader-process-death-check.sh drives with `am kill`.
 */
@RunWith(AndroidJUnit4::class)
class ReaderRestoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val positions = PrefsReadingPositionStore(ctx)
    private lateinit var server: ClipServer

    @Before fun serveABook() {
        server = ClipServer(TestEpub.bytes(), contentType = "application/epub+zip", name = "book.epub")
        // Left in chapter two last time.
        positions.put(ASSET, SEEDED)
    }

    @After fun tearDown() = server.close()

    @Test fun theBookComesBackAtItsPageWhenTheActivityIsRecreated() {
        val intent = ReaderActivity.intent(ctx, ASSET, server.url, "Book", ReaderFormat.EPUB)
        ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
            awaitOpenedAt("chapter two, where it was left")
            // Forget that it reported, so the next report is the recreated reader's own.
            positions.put(ASSET, SEEDED)
            scenario.recreate()
            assertEquals("back and running after the recreate", Lifecycle.State.RESUMED, scenario.state)
            awaitOpenedAt("chapter two again, after the recreate")
        }
    }

    private fun awaitOpenedAt(what: String) = TestEpub.awaitOpenedInChapterTwo(positions, ASSET, what)

    private companion object {
        const val ASSET = "reader-restore-asset"
        const val SEEDED = TestEpub.CHAPTER_TWO
    }
}
