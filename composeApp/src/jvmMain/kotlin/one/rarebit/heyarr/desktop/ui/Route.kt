package one.rarebit.heyarr.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.rarebit.heyarr.core.theme.MediaType

/** Where the app can be. [section] groups a detail under the screen it was opened from for nav highlighting. */
sealed interface Route {
    val section: String get() = javaClass.simpleName

    data class Consume(val experience: Experience) : Route {
        override val section: String get() = experience.title
    }

    data object Home : Route
    data object Discover : Route
    data object Search : Route
    data object Library : Route
    data object Missing : Route
    data object NowPlaying : Route
    data object Settings : Route

    /** The embedded player: one asset of a work, with the work's other episodes as "up next". */
    data class Player(val workId: String, val assetId: String, val blobHash: String, val title: String, val subtitle: String? = null, val typeHint: MediaType = MediaType.MOVIE, val from: String = "Library") : Route {
        override val section: String get() = from
    }

    /** The adaptive detail template for one work. [typeHint]/[titleHint] paint the screen before the fetch lands. */
    data class Detail(val workId: String, val typeHint: MediaType = MediaType.UNKNOWN, val titleHint: String? = null, val from: String = "Library", val curate: Boolean = false) : Route {
        override val section: String get() = from
    }

    /** The (placeholder) e-book reader: one book asset, opened from its detail's "Read" CTA. */
    data class Reader(val workId: String, val assetId: String, val blobHash: String, val title: String, val mime: String? = null, val filename: String? = null, val from: String = "Library") : Route {
        override val section: String get() = from
    }
}

/** A tiny back-stack router: `go` pushes, `back` pops, section routes replace their own kind. */
class Nav(start: Route = Route.Consume(Experience.WATCH)) {
    private val stack = mutableStateListOf<Route>(start)
    var current: Route by mutableStateOf(start)
        private set

    fun go(route: Route) {
        if (route == current) return
        if (route is Route.Player) {
            stack.removeAll { it is Route.Player }
        } else if (route !is Route.Detail) {
            stack.removeAll { it !is Route.Detail && it !is Route.Player && it.section == route.section }
        }
        stack.add(route)
        current = route
    }

    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            current = stack.last()
        }
    }
}
