package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.desktop.ui.components.wantVisible
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-card Want gate ([wantVisible]): a guest never sees a want button that would only
 * be refused (issue #32 item 2 — hide, don't show-then-intercept), while an enrolled client
 * sees it for anything not already in the library. The gating rule itself is owned by
 * `:core` GuestGate (tested in GuestGateTest); this pins the card's use of it.
 */
class GuestCardGatingTest {

    @Test fun guestNeverSeesWant() {
        for (status in listOf(
            null,
            LibraryStatus.NOT_TRACKED,
            LibraryStatus.WANTED,
            LibraryStatus.MISSING,
            LibraryStatus.IN_LIBRARY,
        )) {
            assertFalse(wantVisible(ClientMode.GUEST, status), "guest should not see Want for status=$status")
        }
    }

    @Test fun enrolledSeesWantUnlessAlreadyInLibrary() {
        for (status in listOf(null, LibraryStatus.NOT_TRACKED, LibraryStatus.WANTED, LibraryStatus.MISSING)) {
            assertTrue(wantVisible(ClientMode.ENROLLED, status), "enrolled should see Want for status=$status")
        }
        assertFalse(wantVisible(ClientMode.ENROLLED, LibraryStatus.IN_LIBRARY), "no Want once it is in the library")
    }
}
