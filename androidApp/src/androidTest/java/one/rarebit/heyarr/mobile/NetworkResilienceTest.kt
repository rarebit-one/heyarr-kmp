package one.rarebit.heyarr.mobile

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The film through a bad network (heyarr-kmp#232). A real clip plays through [VideoSession]
 * from a server that can go down (every connection reset, the open ones too, as a lost network
 * resets them) or refuse a lapsed stream token with 410:
 *
 *  - an outage longer than the buffer is ridden out: the film plays what it holds, stalls
 *    with a message saying where it will carry on from, and carries on from there once the
 *    server is back, with no error;
 *  - a stream that lapses mid-film is planned again and carries on at the place.
 *
 * Its `wifi` phase is the same outage made by turning the emulator's Wi-Fi and mobile data off,
 * against a server on the CI host: `androidApp/scripts/network-outage-check.sh` drives it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class NetworkResilienceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()

    // Its own file: a noisy clip the size of a real stream, unlike the plain one other tests share.
    private val clip = File(ctx.cacheDir, "smoke/net-clip.mp4")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: ClipServer? = null
    private lateinit var video: VideoSession
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        // Two minutes, small: more than the 50 s Media3 buffers ahead, so an outage can outlast it.
        if (!clip.exists()) {
            TestClip.write(clip, seconds = CLIP_SECONDS, width = 160, height = 120, fps = 10, noisy = true)
        }
        instrumentation.runOnMainSync { video = VideoSession(ctx, OkHttpClient(), scope) }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { if (it.player !== video.player) it.player = video.player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { video.stop() }
        scenario.close()
        server?.close()
        scope.cancel()
    }

    @Test fun anOutageLongerThanTheBufferIsRiddenOutAndCarriesOnWhereItStalled() {
        val s = throttled()
        play(s.url)
        awaitBufferedAhead(BUFFERED_AHEAD_MS)
        s.down = true
        val stalledAt = rideOutTheOutage()
        s.down = false
        awaitCarriedOn(stalledAt)
    }

    @Test fun aStreamThatLapsesIsPlannedAgainAndCarriesOnAtThePlace() {
        val s = throttled()
        var refreshedAt: Double? = null
        main {
            // What the shell wires: PlaybackCoordinator.refresh plans the file again (unit-tested);
            // here the "plan" answers with the next token.
            video.onRefresh = { at, play ->
                refreshedAt = at
                video.load(nowPlaying(s.url("2")).copy(startSeconds = at, startPaused = !play))
            }
        }
        play(s.url("1"))
        await("playing past 3 s") { video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= 3_000 }
        s.expire("1")
        await("planned again after the 410") { refreshedAt != null }
        val at = (refreshedAt!! * 1000).toLong()
        assertTrue("at the place it lapsed, not the start: $at", at >= 3_000)
        await("carrying on on the fresh stream") {
            video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= at + 2_000
        }
        assertTrue("the fresh token was asked for", (s.requests["2"] ?: 0) >= 1)
        assertNull("no error on the way", main { video.state.error })
    }

    /** Phase `clip`: only [setUp]'s clip, which the script copies to the host to serve. */
    @Test fun writeTheClip() {
        assumeTrue("run by network-outage-check.sh", args.getString("phase") == "clip")
        assertTrue(clip.length() > 0)
    }

    /** Phase `wifi`: the outage is the emulator's Wi-Fi and mobile data turned off by the script. */
    @Test fun aWifiOutageLongerThanTheBufferIsRiddenOut() {
        assumeTrue("run by network-outage-check.sh", args.getString("phase") == "wifi")
        val url = args.getString("url")
        assertNotNull("the script passes the host's clip URL", url)
        play(url!!)
        awaitBufferedAhead(BUFFERED_AHEAD_MS)
        // The script turns the network off on this line, and back on a while after.
        Log.i(WIFI_TAG, WIFI_READY)
        val stalledAt = rideOutTheOutage()
        awaitCarriedOn(stalledAt)
    }

    /**
     * Through the outage: the film plays what it holds, then stalls, saying so, with no error,
     * and holds its place for a while longer. Where it stalled.
     */
    private fun rideOutTheOutage(): Long {
        await("stalled, once the buffer ran dry, saying it is reconnecting", OUTAGE_TIMEOUT_MS) {
            video.state.buffering && video.state.reconnecting != null
        }
        val stalledAt = main { video.player!!.currentPosition }
        val said = main { video.state.reconnecting!! }
        assertTrue("says where it carries on from: $said", said.contains("carries on from"))
        val until = SystemClock.uptimeMillis() + STALL_MS
        while (SystemClock.uptimeMillis() < until) {
            assertNull("no error while it waits", main { video.state.error })
            assertTrue("holds its place", main { video.player!!.currentPosition } >= stalledAt - SLACK_MS)
            SystemClock.sleep(POLL_MS * 10)
        }
        return stalledAt
    }

    private fun awaitCarriedOn(stalledAt: Long) {
        await("carrying on from where it stalled", RECOVER_TIMEOUT_MS) {
            video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= stalledAt + 3_000
        }
        assertNull("no error", main { video.state.error })
        await("the message goes once it is loading again") { video.state.reconnecting == null }
    }

    /** A server that sends the clip at [REALTIME_MULTIPLE] times its own pace, as a stream arrives. */
    private fun throttled(): ClipServer {
        val bytes = clip.readBytes()
        return ClipServer(bytes).also {
            it.bytesPerSecond = bytes.size.toLong() * REALTIME_MULTIPLE / CLIP_SECONDS
            server = it
        }
    }

    private fun play(url: String) {
        main { video.load(nowPlaying(url)) }
        await("playing") { video.player?.isPlaying == true }
    }

    private fun nowPlaying(url: String) = NowPlaying(
        target = PlaybackTarget(url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
        title = "Clip",
        assetId = "net-asset",
    )

    private fun awaitBufferedAhead(ms: Long) = await("${ms}ms buffered ahead") {
        val p = video.player
        p != null && p.isPlaying && p.bufferedPosition - p.currentPosition >= ms
    }

    private fun <T> main(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(what: String, timeoutMs: Long = TIMEOUT_MS, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!main(condition)) {
            val late = SystemClock.uptimeMillis() > deadline
            if (late) throw AssertionError("timed out waiting: $what (state ${main { video.state }})")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WIFI_TAG = "HeyarrNetwork"
        const val WIFI_READY = "ready for the network to go"
        const val CLIP_SECONDS = 120
        const val REALTIME_MULTIPLE = 3
        const val BUFFERED_AHEAD_MS = 30_000L
        const val STALL_MS = 15_000L
        const val SLACK_MS = 1_000L
        const val OUTAGE_TIMEOUT_MS = 90_000L
        const val RECOVER_TIMEOUT_MS = 60_000L
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
