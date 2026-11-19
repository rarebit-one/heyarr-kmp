package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.PlaybackSession
import one.rarebit.heyarr.desktop.state.castBlocks
import one.rarebit.heyarr.desktop.ui.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * heyarr-kmp#248: while this app casts a film, the local player must not open a second
 * stream of it. The receiver wedged right as a second transcode of the same film started.
 */
class CastPlaybackGateTest {
    private val film = Route.Player("work", "film", "hash", "Film", typeHint = MediaType.MOVIE)

    private fun casting(assetId: String = "film") = PlaybackSession().apply {
        castingTo = { if (it == assetId) "Living room" else null }
    }

    @Test
    fun theFilmOnAReceiverDoesNotStartLocally() {
        val playback = casting()
        assertTrue(playback.castBlocks(film))
        assertFalse(playback.play(film))
        assertNull(playback.current)
        assertFalse(playback.pendingStart, "Nothing may ask the host to open a stream")
    }

    @Test
    fun otherItemsStillPlayHere() {
        val playback = casting()
        val other = film.copy(assetId = "other")
        assertTrue(playback.play(other))
        assertEquals(other, playback.current)
        // Music is never cast by this app, so an asset id collision cannot hold it.
        assertTrue(playback.play(film.copy(typeHint = MediaType.MUSIC)))
    }

    @Test
    fun onceCastingStopsTheFilmPlaysHereAgain() {
        var receiver: String? = "Living room"
        val playback = PlaybackSession().apply { castingTo = { receiver } }
        assertFalse(playback.play(film))
        receiver = null
        assertTrue(playback.play(film))
        assertTrue(playback.pendingStart)
    }
}
