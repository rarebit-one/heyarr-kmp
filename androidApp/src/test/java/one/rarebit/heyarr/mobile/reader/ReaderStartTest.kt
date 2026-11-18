package one.rarebit.heyarr.mobile.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the reader opens its book in place and when it hands it to the shell (heyarr-kmp#240). */
class ReaderStartTest {
    @Test fun aFirstOpenOpensInPlace() {
        assertEquals(
            "opened from the shell",
            ReaderStart.OPEN,
            ReaderStart.of(restored = false, firstScreenInProcess = false),
        )
        // A test, or anything else, that opens the reader on its own in a fresh process.
        assertEquals(
            "opened on its own",
            ReaderStart.OPEN,
            ReaderStart.of(restored = false, firstScreenInProcess = true),
        )
    }

    @Test fun aRotationOpensInPlace() {
        // The shell (or an earlier reader) was created in this process, so it is signed in.
        assertEquals(ReaderStart.OPEN, ReaderStart.of(restored = true, firstScreenInProcess = false))
    }

    @Test fun aReaderBroughtBackAfterProcessDeathHandsTheBookToTheShell() {
        // Saved state, and nothing created before it: Android started the process to show it,
        // with the shell that signs the process in left as a record under it.
        assertEquals(ReaderStart.HAND_TO_SHELL, ReaderStart.of(restored = true, firstScreenInProcess = true))
    }
}
