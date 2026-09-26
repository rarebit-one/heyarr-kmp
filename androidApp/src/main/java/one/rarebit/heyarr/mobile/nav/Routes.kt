package one.rarebit.heyarr.mobile.nav

import kotlinx.serialization.Serializable
import one.rarebit.heyarr.core.theme.MediaType

/**
 * The signed-in navigation graph, as typed routes.
 *
 * The rule that matters: a route carries **identifiers and display hints only**.
 * Never a [one.rarebit.heyarr.mobile.playback.PlaybackTarget], never a
 * [one.rarebit.heyarr.core.auth.Credential] — those live in app state and a route
 * re-resolves them by id. [Player] is argless for exactly that reason: what is
 * playing lives in the video session or the audio queue, the route only puts the
 * player in front.
 */
sealed interface Route {

    // ── Top level: Home · Search · Library · Manage ─────────────────────────────
    @Serializable data object Home : Route

    /** Mobile management hub for Missing, Discover, Cast and Settings destinations. */
    @Serializable data object Manage : Route

    @Serializable data object Discover : Route

    @Serializable data object Search : Route

    @Serializable data object Library : Route

    @Serializable data object Missing : Route

    @Serializable data object Cast : Route

    @Serializable data object Settings : Route

    /**
     * The adaptive detail template for one work. [type] (a [MediaType] name) and
     * [title] paint the screen before the fetch lands; [from] labels the back button;
     * [curate] opens on the Curate tab.
     */
    @Serializable data class Detail(
        val workId: String,
        val type: String = "UNKNOWN",
        val title: String? = null,
        val from: String = "Library",
        val curate: Boolean = false,
    ) : Route {
        val typeHint: MediaType get() = runCatching { MediaType.valueOf(type) }.getOrDefault(MediaType.UNKNOWN)
    }

    // ── Settings' sub-screens ────────────────────────────────────────────────────

    /** The connection telemetry sheet (`/session`, `/providers`, `/capabilities`, peers, libraries, jobs). */
    @Serializable data object Telemetry : Route

    /** This phone's Voidbind device enrolment. */
    @Serializable data object Device : Route

    // ── Playlists (encrypted personal state, decrypted on this device) ───────────
    @Serializable data object Playlists : Route

    @Serializable data class Playlist(val spaceId: String, val title: String? = null) : Route

    // ── Full-screen ──────────────────────────────────────────────────────────────

    /** The player. Argless: the item lives in the video session or the audio queue. */
    @Serializable data object Player : Route

    companion object {
        /** Routes that own the whole screen: no bar, no rail, no now-playing strip. */
        fun isFullScreen(route: Route?): Boolean = route is Player
    }
}

/** A detail route for [workId], carrying the kind and title the caller already knows. */
fun detailRoute(
    workId: String,
    type: MediaType,
    title: String?,
    from: String = "Library",
    curate: Boolean = false,
): Route.Detail = Route.Detail(workId, type.name, title, from, curate)
