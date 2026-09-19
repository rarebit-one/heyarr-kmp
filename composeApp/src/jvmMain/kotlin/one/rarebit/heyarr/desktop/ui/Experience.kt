package one.rarebit.heyarr.desktop.ui

import one.rarebit.heyarr.core.theme.MediaType

/** Consumption shelves share the catalog, artwork and details with management. */
enum class Experience(val title: String, val kinds: Set<MediaType>) {
    WATCH("Watch", setOf(MediaType.MOVIE, MediaType.SERIES)),
    LISTEN("Listen", setOf(MediaType.MUSIC, MediaType.AUDIOBOOK, MediaType.PODCAST)),
    READ("Read", setOf(MediaType.BOOK, MediaType.FEED)),
}

fun MediaType.isListening(): Boolean = this in Experience.LISTEN.kinds

/** Keep the reading/browsing pane usable; narrow windows retain the compact transport. */
fun showAudioDock(type: MediaType, active: Boolean, widthDp: Float, fullscreen: Boolean): Boolean =
    active && type.isListening() && widthDp >= 1100f && !fullscreen
