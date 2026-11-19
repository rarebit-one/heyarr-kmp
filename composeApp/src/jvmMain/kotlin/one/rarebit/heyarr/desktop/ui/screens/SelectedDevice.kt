package one.rarebit.heyarr.desktop.ui.screens

import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.desktop.cast.CastDevice

/**
 * The one device Now Playing is showing. A DLNA renderer and a Google Cast receiver are driven
 * by different machinery (the node's `playback_status` / `control_playback` against the renderer
 * it found; this client's own Cast connection to the receiver), but only one is ever on screen,
 * so the selection is one value rather than two nullable fields that had to be kept mutually
 * exclusive by hand.
 */
internal sealed interface SelectedDevice {
    /** A renderer the node discovered and drives over UPnP/DLNA. */
    data class Dlna(val renderer: Renderer) : SelectedDevice

    /** A Google Cast receiver this client drives itself. */
    data class Cast(val device: CastDevice) : SelectedDevice

    companion object {
        /**
         * What to show when nothing is picked yet: the receiver already being cast to, else the
         * first renderer the node found, else the first Cast receiver on the network.
         */
        fun initial(casting: CastDevice?, renderers: List<Renderer>, receivers: List<CastDevice>): SelectedDevice? =
            casting?.let(::Cast) ?: renderers.firstOrNull()?.let(::Dlna) ?: receivers.firstOrNull()?.let(::Cast)
    }
}

/** The DLNA renderer, when that is what is selected; keys the node-polling path. */
internal val SelectedDevice?.dlnaRenderer: Renderer? get() = (this as? SelectedDevice.Dlna)?.renderer

/** The Cast receiver, when that is what is selected; keys the receiver-polling path. */
internal val SelectedDevice?.castReceiver: CastDevice? get() = (this as? SelectedDevice.Cast)?.device

/**
 * Follow the Cast manager's own receiver. Casting from anywhere (a player's Cast menu, a
 * receiver this screen attached to) brings its controls forward; when the manager lets its
 * receiver go, a Cast selection goes with it, but a picked DLNA renderer stays.
 */
internal fun SelectedDevice?.following(casting: CastDevice?): SelectedDevice? = when {
    casting != null -> SelectedDevice.Cast(casting)
    this is SelectedDevice.Cast -> null
    else -> this
}
