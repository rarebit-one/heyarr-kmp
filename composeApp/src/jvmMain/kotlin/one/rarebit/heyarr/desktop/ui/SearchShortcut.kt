package one.rarebit.heyarr.desktop.ui

import java.awt.event.KeyEvent

/** Window-level dispatch works even before a Compose control owns keyboard focus. */
internal fun dispatchSearchShortcut(event: KeyEvent, enabled: Boolean, onSearch: () -> Unit): Boolean {
    if (!enabled || event.id != KeyEvent.KEY_PRESSED || event.keyCode != KeyEvent.VK_F ||
        !event.isControlDown || event.isMetaDown || event.isAltDown || event.isShiftDown) return false
    onSearch()
    event.consume()
    return true
}
