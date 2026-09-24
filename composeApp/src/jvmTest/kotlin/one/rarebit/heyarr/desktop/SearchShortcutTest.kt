package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.ui.dispatchSearchShortcut
import java.awt.Canvas
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.*

class SearchShortcutTest {
    private val source = Canvas() // No window, native peer or Compose focus owner.
    private fun event(id: Int, modifiers: Int, key: Int = KeyEvent.VK_F) =
        KeyEvent(source, id, 0, modifiers, key, KeyEvent.CHAR_UNDEFINED)

    @Test fun searchOpensWithoutAControlOwningFocusAndConsumesOnlyThePress() {
        var opens = 0
        val press = event(KeyEvent.KEY_PRESSED, InputEvent.CTRL_DOWN_MASK)
        assertTrue(dispatchSearchShortcut(press, true) { opens++ })
        assertTrue(press.isConsumed)
        val release = event(KeyEvent.KEY_RELEASED, InputEvent.CTRL_DOWN_MASK)
        assertFalse(dispatchSearchShortcut(release, true) { opens++ })
        assertEquals(1, opens)
    }

    @Test fun reservedModifiersOrdinaryTypingAndModalDialogsDoNotOpenSearch() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        for (modifiers in listOf(
            0,
            InputEvent.META_DOWN_MASK,
            ctrl or InputEvent.META_DOWN_MASK,
            ctrl or InputEvent.ALT_DOWN_MASK,
            ctrl or InputEvent.SHIFT_DOWN_MASK,
        )) {
            val key = event(KeyEvent.KEY_PRESSED, modifiers)
            assertFalse(dispatchSearchShortcut(key, true) { fail("Unexpected search for modifiers=$modifiers") })
            assertFalse(key.isConsumed)
        }
        assertFalse(dispatchSearchShortcut(event(KeyEvent.KEY_PRESSED, ctrl), false) { fail("Modal dialog lost focus") })
        assertFalse(dispatchSearchShortcut(event(KeyEvent.KEY_PRESSED, ctrl, KeyEvent.VK_K), true) { fail("Old shortcut still bound") })
    }
}
