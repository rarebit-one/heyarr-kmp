package one.rarebit.heyarr.mobile

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.library.WorkAsset
import one.rarebit.heyarr.mobile.nav.Decisions
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.ui.components.NavSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shell's decisions are pure: what the player route shows, when the bar shows, which tile lights, how an album queues. */
class DecisionsTest {

    private val hash = "blake3:" + "a".repeat(64)
    private val video = NowPlaying(PlaybackTarget("https://n/api/v1/blobs/$hash/content", Credential.Session("t"), isVideo = true), "Film")
    private val track = AudioItem(assetId = "a1", workId = "w", title = "One", contentUrl = "https://n/api/v1/blobs/$hash/content")

    @Test fun videoPreEmptsTheAudioQueueAndNothingMeansLeave() {
        assertEquals(Decisions.PlayerContent.VIDEO, Decisions.playerContent(video, track))
        assertEquals(Decisions.PlayerContent.AUDIO, Decisions.playerContent(null, track))
        assertEquals(Decisions.PlayerContent.NONE, Decisions.playerContent(null, null))
    }

    @Test fun theBarShowsForEitherPlayerButNeverOverThePlayer() {
        assertTrue(Decisions.showNowPlayingBar(fullScreen = false, videoActive = true, audioItem = null))
        assertTrue(Decisions.showNowPlayingBar(fullScreen = false, videoActive = false, audioItem = track))
        assertFalse(Decisions.showNowPlayingBar(fullScreen = true, videoActive = true, audioItem = track))
        assertFalse(Decisions.showNowPlayingBar(fullScreen = false, videoActive = false, audioItem = null))
    }

    @Test fun aDetailLightsTheSectionItWasOpenedFrom() {
        assertEquals(NavSection.HOME, Decisions.section(Route.Home))
        assertEquals(NavSection.SETTINGS, Decisions.section(Route.Telemetry))
        assertEquals(NavSection.LIBRARY, Decisions.section(Route.Playlists))
        assertEquals(NavSection.SEARCH, Decisions.section(detailRoute("w", MediaType.MOVIE, "T", from = "Search")))
        assertEquals(NavSection.LIBRARY, Decisions.section(detailRoute("w", MediaType.MOVIE, "T", from = "Downloads")))
        assertNull(Decisions.section(Route.Player))
        for (s in NavSection.entries) assertEquals(s, Decisions.section(Decisions.routeOf(s)))
    }

    @Test fun anAlbumQueuesItsPlayableTracksAndKeepsTheStartTrack() {
        val work = Work(id = "w", title = "Kid A", kind = "music", artist = "Radiohead", artworkPath = "/api/v1/blobs/$hash/content")
        val tracks = listOf(
            WorkAsset(id = "t1", editionId = "e", filename = "01 - Everything.flac", mime = "audio/flac", blobHash = hash),
            WorkAsset(id = "t2", editionId = "e", filename = "02 - Kid A.flac", mime = "audio/flac", blobHash = null),
            WorkAsset(id = "t3", editionId = "e", filename = "03 - The National Anthem.flac", mime = "audio/flac", blobHash = hash),
        )
        val (items, index) = Decisions.queueFor("https://n", work, tracks, start = 2)
        assertEquals(listOf("t1", "t3"), items.map { it.assetId })
        assertEquals(1, index)
        assertEquals("The National Anthem", items[1].title)
        assertEquals("Radiohead", items[1].artist)
        assertEquals("https://n/api/v1/blobs/$hash/content", items[0].contentUrl)
        assertEquals("https://n/api/v1/blobs/$hash/content", items[0].artworkUrl)
    }
}
