package one.rarebit.heyarr.mobile.nav

import one.rarebit.heyarr.mobile.catalog.Artwork
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.music.trackTitle
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackClient
import one.rarebit.heyarr.mobile.ui.components.NavSection

/**
 * The navigation host's decisions, as pure functions — so what the player route shows,
 * when the now-playing bar appears, which nav tile a route lights and how an album
 * becomes a queue are unit-tested rather than discovered on a device. The host only
 * wires them.
 */
object Decisions {

    /** What the full-screen player route renders: video pre-empts the audio queue; nothing means leave. */
    enum class PlayerContent { VIDEO, AUDIO, NONE }

    fun playerContent(nowPlaying: NowPlaying?, audioItem: AudioItem?): PlayerContent = when {
        nowPlaying != null -> PlayerContent.VIDEO
        audioItem != null -> PlayerContent.AUDIO
        else -> PlayerContent.NONE
    }

    /** The persistent bar shows while either player has something and the screen is not already the player. */
    fun showNowPlayingBar(fullScreen: Boolean, videoActive: Boolean, audioItem: AudioItem?): Boolean = !fullScreen && (videoActive || audioItem != null)

    /** The nav tile a route lights: a detail lights the section it was opened from. */
    fun section(route: Route?): NavSection? = when (route) {
        Route.Home -> NavSection.HOME

        Route.Discover -> NavSection.DISCOVER

        Route.Search -> NavSection.SEARCH

        Route.Library, Route.Playlists, is Route.Playlist -> NavSection.LIBRARY

        Route.Missing -> NavSection.MISSING

        Route.Cast -> NavSection.CAST

        Route.Settings, Route.Telemetry, Route.Device -> NavSection.SETTINGS

        is Route.Detail -> when (route.from) {
            "Home" -> NavSection.HOME
            "Discover" -> NavSection.DISCOVER
            "Search" -> NavSection.SEARCH
            "Missing" -> NavSection.MISSING
            else -> NavSection.LIBRARY
        }

        Route.Player, null -> null
    }

    /** The route a nav tile goes to. */
    fun routeOf(section: NavSection): Route = when (section) {
        NavSection.HOME -> Route.Home
        NavSection.DISCOVER -> Route.Discover
        NavSection.SEARCH -> Route.Search
        NavSection.LIBRARY -> Route.Library
        NavSection.MISSING -> Route.Missing
        NavSection.CAST -> Route.Cast
        NavSection.SETTINGS -> Route.Settings
    }

    /**
     * An album's tracks as queue items: the blob route per track, the album's cover as
     * every track's artwork, the album's artist. A track without a blob is dropped (it
     * cannot be streamed), and the start index is re-pointed at the same track when
     * earlier ones fell away.
     */
    fun queueFor(baseUrl: String, work: Work, tracks: List<WorkAsset>, start: Int): Pair<List<AudioItem>, Int> {
        val playable = tracks.filter { !it.blobHash.isNullOrBlank() }
        val items = playable.map { t ->
            AudioItem(
                assetId = t.id,
                workId = work.id,
                title = trackTitle(t),
                artist = work.artist ?: work.author,
                album = work.title,
                artworkUrl = Artwork.posterUrl(baseUrl, work),
                contentUrl = PlaybackClient.blobContentUrl(baseUrl, t.blobHash!!),
                mime = t.mime,
            )
        }
        val wanted = tracks.getOrNull(start)?.id
        val index = playable.indexOfFirst { it.id == wanted }.takeIf { it >= 0 } ?: 0
        return items to index
    }
}
