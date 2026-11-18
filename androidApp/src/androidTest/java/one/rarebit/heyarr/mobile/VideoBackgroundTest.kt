package one.rarebit.heyarr.mobile

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The film in the background, on a device (#231). A Nothing Phone back from Recents found its
 * hardware decoder reclaimed and the player driving it anyway; Play could not get out of the
 * error. Only a device has the codec, the surface and the activity lifecycle, so this plays a
 * real H.264 clip (encoded here, served over local HTTP through the app's own data source)
 * through [VideoSession] in a `PlayerView`, presses HOME and comes back.
 *
 * What it cannot show: a HARDWARE decoder being reclaimed by another app. The emulator's
 * decoder is software and nothing competes for it, which is why the failure is forced
 * through [VideoSession.failForTest] for the recovery cases.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoBackgroundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val clip = File(ctx.cacheDir, "smoke/clip.mp4")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var server: ClipServer
    private lateinit var video: VideoSession
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun playAClip() {
        TestClip.write(clip)
        server = ClipServer(clip.readBytes())
        instrumentation.runOnMainSync { video = VideoSession(ctx, OkHttpClient(), scope) }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            // Wired as MainActivity wires it, and shown as the player screen shows it.
            activity.lifecycle.addObserver(video.lifecycleObserver { activity.isChangingConfigurations })
            activity.setContent {
                AndroidView(
                    factory = { PlayerView(it).apply { useController = false } },
                    update = { if (it.player !== video.player) it.player = video.player },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        instrumentation.runOnMainSync {
            video.load(
                NowPlaying(
                    target = PlaybackTarget(server.url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
                    title = "clip",
                ),
            )
        }
    }

    @After fun stop() {
        instrumentation.runOnMainSync { video.stop() }
        scenario.close()
        server.close()
        scope.cancel()
    }

    @Test fun aFilmPausesInTheBackgroundAndCarriesOnFromTheSamePlaceOnReturn() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        var task = 0
        scenario.onActivity { task = it.taskId }

        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("the player let go of its decoder in the background") {
            video.player == null && video.state.paused
        }
        val left = main { video.state.positionMs }
        // A while behind other apps: nothing moves, nothing is held.
        SystemClock.sleep(BACKGROUND_MS)
        assertEquals("paused where it was left", left, main { video.state.positionMs })
        assertNull("no player, so no codec, while in the background", main { video.player })

        backFromRecents(task)
        await("playing again on return") { video.player?.isPlaying == true }
        // Its own clock, not the held scrubber: it sought back to where it was, not to 0.
        val resumedAt = main { video.player!!.currentPosition }
        assertTrue("resumed at $resumedAt, left at $left", resumedAt >= left - SEEK_SLACK_MS)
        awaitPlayingPast(left + PLAYED_AFTER_MS)
        assertNull("no error on return", main { video.state.error })
    }

    /**
     * The screen going off mid-film, then on again (#232 scenario 4): the same stop as HOME, so
     * the film pauses and lets go of its decoder while the screen is off, and carries on from
     * the same place once the phone is woken and unlocked.
     */
    @Test fun aFilmPausesWhileTheScreenIsOffAndCarriesOnWhenItComesBack() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        val ui = instrumentation.uiAutomation
        ui.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
        await("let go of its decoder with the screen off") { video.player == null && video.state.paused }
        val left = main { video.state.positionMs }
        SystemClock.sleep(SCREEN_OFF_MS)
        assertEquals("paused where it was left", left, main { video.state.positionMs })
        ui.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        ui.executeShellCommand("wm dismiss-keyguard").close()
        await("playing again once the phone is woken") { video.player?.isPlaying == true }
        val resumedAt = main { video.player!!.currentPosition }
        assertTrue("resumed at $resumedAt, left at $left", resumedAt >= left - SEEK_SLACK_MS)
        assertNull("no error on return", main { video.state.error })
    }

    @Test fun aDecoderFailureIsRetriedOnceByItself() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        val failedAt = main { video.state.positionMs }
        main { video.failForTest(decoderFailure()) }
        await("re-prepared and playing again") { video.player?.isPlaying == true && video.state.error == null }
        assertTrue("from where it failed", main { video.player!!.currentPosition } >= failedAt - SEEK_SLACK_MS)
    }

    @Test fun aSecondFailureIsShownInFullAndPlayRecoversIt() {
        awaitPlayingPast(PLAYED_BEFORE_MS)
        main { video.failForTest(decoderFailure()) }
        await("the automatic retry is playing") { video.player?.isPlaying == true }
        main { video.failForTest(decoderFailure()) }
        await("the second failure in a row is left up") { video.state.error != null }
        val error = main { video.state.error!! }
        assertTrue("the whole text, code and detail: $error", error.endsWith("ERROR_CODE_DECODING_FAILED: forced"))
        assertEquals("a dead player until Play", Player.STATE_IDLE, main { video.player!!.playbackState })
        val stoppedAt = main { video.state.positionMs }

        // The bug: Play did nothing here.
        main { video.togglePause() }
        await("Play re-prepared it") { video.player?.isPlaying == true && video.state.error == null }
        assertTrue("where it stopped", main { video.player!!.currentPosition } >= stoppedAt - SEEK_SLACK_MS)
    }

    private fun backFromRecents(task: Int) = TestClip.backFromRecents(instrumentation, scenario, task)

    private fun decoderFailure() = PlaybackException("forced", null, PlaybackException.ERROR_CODE_DECODING_FAILED)

    private fun awaitPlayingPast(positionMs: Long) = await("playing past ${positionMs}ms") {
        video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= positionMs
    }

    private fun <T> main(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!main(condition)) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("timed out waiting: $what (state ${main { video.state }})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val PLAYED_BEFORE_MS = 3_000L
        const val PLAYED_AFTER_MS = 2_000L
        const val BACKGROUND_MS = 5_000L
        const val SCREEN_OFF_MS = 3_000L
        const val SEEK_SLACK_MS = 1_000L
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 100L
    }
}
