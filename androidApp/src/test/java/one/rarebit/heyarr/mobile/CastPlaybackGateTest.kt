package one.rarebit.heyarr.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.playback.InMemoryResumeStore
import one.rarebit.heyarr.core.playback.ResumeItem
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.mobile.cast.CastGate
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's side of the gate (heyarr-kmp#250): what Play does while Google Cast is
 * up. The film is planned and its resume point found as for this phone, then handed over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CastPlaybackGateTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val hash = "blake3:" + "a".repeat(64)

    private class Sink(var route: CastGate.Route) : PlaybackCoordinator.CastSink {
        val sent = mutableListOf<NowPlaying>()

        override fun route(np: NowPlaying) = route

        override fun send(np: NowPlaying) {
            sent += np
        }

        override val device = "Living room"
    }

    private fun coordinator(sink: Sink?) = PlaybackCoordinator(
        RoutedTransport(emptyMap()),
        { "https://h.example" },
        { Credential.Session("tok") },
        CoroutineScope(dispatcher),
        dispatcher,
    ).also { it.cast = sink }

    @Test fun withASessionUpPlaySendsTheFilmToTheTvFromItsResumePoint() {
        val sink = Sink(CastGate.Route.CAST)
        val c = coordinator(sink)
        c.resume = InMemoryResumeStore().apply {
            put(
                ResumePoint(
                    ResumeItem("a1", title = "Film"),
                    positionSeconds = 1500.0,
                    durationSeconds = 7200.0,
                    updatedAtMs = 1,
                ),
            )
        }
        c.playFile("Film", "a1", hash, "video/mp4", "movie")
        assertNull("this phone's player stays closed", c.nowPlaying.value)
        assertEquals("a1", sink.sent.single().assetId)
        assertEquals("film-absolute, where it was left", 1500.0, sink.sent.single().startSeconds, 0.0)
    }

    @Test fun theFilmBeingCastCannotOpenHereBesideIt() {
        val c = coordinator(Sink(CastGate.Route.BLOCKED))
        c.playFile("Film", "a1", hash, "video/mp4", "movie")
        assertNull(c.nowPlaying.value)
        assertTrue(c.notice.value!!.startsWith("Playing on Living room"))
    }

    @Test fun otherwiseItPlaysHereAsBefore() {
        for (sink in listOf(null, Sink(CastGate.Route.LOCAL))) {
            val c = coordinator(sink)
            c.playFile("Film", "a1", hash, "video/mp4", "movie")
            assertEquals("a1", c.nowPlaying.value?.assetId)
            assertTrue(sink?.sent.orEmpty().isEmpty())
        }
    }
}
