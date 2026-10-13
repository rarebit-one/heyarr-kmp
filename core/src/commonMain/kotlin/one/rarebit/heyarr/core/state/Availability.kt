package one.rarebit.heyarr.core.state

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.auth.GuestGate
import one.rarebit.heyarr.core.auth.Surface

/**
 * What a work means to someone who wants to watch, listen or read it — the consumer face of
 * [LibraryStatus]. Wants, profiles and monitoring stay behind Manage; this only answers "can I
 * play it, is it coming, can I get it". Derived from real state alone, never assumed:
 *
 *  - [READY]          there are bytes to play (or a want is satisfied). Shown as no mark at all.
 *  - [ON_ITS_WAY]     a want exists and heyarr is working on it.
 *  - [NOT_AVAILABLE]  a want exists and nothing acceptable has been found yet.
 *  - [GETTABLE]       nothing held and nothing wanted: the work can be asked for.
 */
enum class Availability(val label: String) {
    READY("Ready"),
    ON_ITS_WAY("On its way"),
    NOT_AVAILABLE("Not available yet"),
    GETTABLE("Not in your library"),
    ;

    /** Whether to offer "Get it": only for something not held or wanted, and only where a want is allowed. */
    fun offersGetIt(mode: ClientMode): Boolean = this == GETTABLE && GuestGate.allows(mode, Surface.WANT)

    companion object {
        /**
         * [playable] is whether the catalog holds a file for the work — a scanned work no want names
         * is still ready to play. Otherwise the want status decides; an unknown acquisition state
         * already reads as in progress in [LibraryStatus.ofState], so it never shows as ready.
         */
        fun of(status: LibraryStatus, playable: Boolean = false): Availability = when {
            playable || status == LibraryStatus.IN_LIBRARY -> READY
            status == LibraryStatus.WANTED -> ON_ITS_WAY
            status == LibraryStatus.MISSING -> NOT_AVAILABLE
            else -> GETTABLE
        }

        /**
         * A work the library catalogues, with no file information to hand (a search hit, a type
         * rail). The catalog only holds works a scan or a want put there, so one that no want
         * names came from a scan and is held; a want's own state decides the rest.
         */
        fun ofCatalogued(status: LibraryStatus): Availability =
            of(status, playable = status == LibraryStatus.NOT_TRACKED)
    }
}
