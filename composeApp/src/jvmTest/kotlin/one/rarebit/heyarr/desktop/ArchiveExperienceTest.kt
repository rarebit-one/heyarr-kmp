package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.ui.*
import kotlin.test.*

class ArchiveExperienceTest {
    private fun track(id: String, type: MediaType = MediaType.MUSIC) = Route.Player("album", id, "hash-$id", "Album", id, type)

    @Test fun consumptionShelvesAreDisjointAndCoverPlayableKinds() {
        val kinds = Experience.entries.flatMap { it.kinds }
        assertEquals(kinds.size, kinds.toSet().size)
        assertEquals(setOf(MediaType.MOVIE, MediaType.SERIES), Experience.WATCH.kinds)
        assertTrue(MediaType.AUDIOBOOK in Experience.LISTEN.kinds)
        assertTrue(MediaType.FEED in Experience.READ.kinds)
        assertFalse(MediaType.BOOK.isListening())
    }

    @Test fun audioDockRequiresAudioAndEnoughRoomForTheReader() {
        for (type in MediaType.entries) {
            for (width in listOf(760f, 1099f, 1100f, 1600f)) {
                assertEquals(type.isListening() && width >= 1100, showAudioDock(type, true, width, false), "$type/$width")
                assertFalse(showAudioDock(type, false, width, false))
                assertFalse(showAudioDock(type, true, width, true))
            }
        }
    }

    @Test fun browsingAndReadingDoNotReplaceTheAudioQueue() {
        val playback = PlaybackSession()
        val tracks = listOf(track("one"), track("two"), track("three"))
        playback.queueAudio(tracks, 1)
        val nav = Nav(Route.Consume(Experience.LISTEN))
        nav.go(Route.Consume(Experience.READ))
        nav.go(Route.Reader("book", "page", "bookhash", "Book"))
        assertEquals(tracks[1], playback.current)
        assertEquals(tracks[0], playback.previousAudio())
        assertEquals(tracks[2], playback.next())
        playback.play(playback.next()!!)
        assertNull(playback.next())
        assertEquals(tracks, playback.audioQueue)
        nav.back()
        assertEquals(Route.Consume(Experience.READ), nav.current)
    }

    @Test fun replacingAudioWithVideoClearsAudioQueueAndStopClearsAll() {
        val playback = PlaybackSession()
        playback.queueAudio(listOf(track("a"), track("b")))
        playback.play(track("film", MediaType.MOVIE))
        assertTrue(playback.audioQueue.isEmpty())
        assertNull(playback.next())
        playback.stop()
        assertFalse(playback.active)
        assertFalse(playback.pendingStart)
        assertNull(playback.loadedAssetId)
    }

    @Test fun invalidQueueStartDoesNotInterruptTheCurrentTrack() {
        val playback = PlaybackSession()
        val current = track("current")
        playback.queueAudio(listOf(current))
        playback.queueAudio(emptyList())
        playback.queueAudio(listOf(track("new")), -1)
        playback.queueAudio(listOf(track("film", MediaType.MOVIE)))
        assertEquals(current, playback.current)
        assertEquals(listOf(current), playback.audioQueue)
    }
}
