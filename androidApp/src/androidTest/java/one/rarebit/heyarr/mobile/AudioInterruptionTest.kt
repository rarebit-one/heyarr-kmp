package one.rarebit.heyarr.mobile

import android.media.AudioFocusRequest
import android.media.AudioManager
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
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import android.media.AudioAttributes as PlatformAudioAttributes

/**
 * Interruptions (heyarr-kmp#232): both players hand audio focus and "becoming noisy" to
 * Media3 (`handleAudioFocus`, `setHandleAudioBecomingNoisy`), and this pins what that does on
 * a device, for the film (`VideoSession`, MOVIE audio) and the music queue (`PlaybackService`,
 * MUSIC audio), the way Android's own players behave:
 *
 *  - a call (transient focus) pauses, and playback carries on when it ends;
 *  - a navigation prompt (transient, may duck) lowers the volume and does not pause;
 *  - another media app taking focus for good pauses, and it stays paused;
 *  - headphones or Bluetooth going away (`ACTION_AUDIO_BECOMING_NOISY`) pauses.
 *
 * Focus is taken from inside the test, as another app would take it: Android keeps a focus
 * owner per request, not per app. The noisy broadcast is a protected one only the system can
 * send, so those two cases run from `androidApp/scripts/audio-noisy-check.sh` (phase `noisy`),
 * which sends it from a root shell; they are skipped in the ordinary connected run.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class AudioInterruptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val phase: String? = InstrumentationRegistry.getArguments().getString("phase")
    private val audioManager = ctx.getSystemService(AudioManager::class.java)
    private val audio get() = (ctx.applicationContext as HeyarrApp).graph.audio
    private val clip = File(ctx.cacheDir, "smoke/clip.mp4")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var server: ClipServer
    private lateinit var video: VideoSession
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private var taken: AudioFocusRequest? = null

    @Before fun setUp() {
        if (!clip.exists()) TestClip.write(clip)
        server = ClipServer(clip.readBytes())
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
        giveFocusBack()
        instrumentation.runOnMainSync {
            video.stop()
            audio.stop()
        }
        scenario.close()
        server.close()
        scope.cancel()
    }

    // ── The film ────────────────────────────────────────────────────────────────────

    @Test fun aCallPausesTheFilmAndItCarriesOnWhenTheCallEnds() {
        playFilm()
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, call)
        await("paused for the call") { video.player?.isPlaying == false && video.state.paused }
        assertTrue("held, not stopped: it means to carry on", main { video.player!!.playWhenReady })
        giveFocusBack()
        await("playing again after the call") { video.player?.isPlaying == true }
    }

    @Test fun aNavigationPromptLowersTheFilmWithoutPausingIt() {
        playFilm()
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, prompt)
        SystemClock.sleep(HOLD_MS)
        assertTrue("a film ducks under a prompt rather than stopping", main { video.player!!.isPlaying })
    }

    @Test fun anotherMediaAppPausesTheFilmForGood() {
        playFilm()
        takeFocus(AudioManager.AUDIOFOCUS_GAIN, media)
        await("paused for the other app") { video.player?.playWhenReady == false && video.state.paused }
        giveFocusBack()
        SystemClock.sleep(HOLD_MS)
        assertFalse("it does not start again by itself", main { video.player!!.isPlaying })
    }

    @Test fun headphonesGoingAwayPauseTheFilm() {
        assumeTrue("run by audio-noisy-check.sh", phase == "noisy")
        playFilm()
        awaitTheHeadphonesGoing()
        await("paused when the headphones went") { video.player?.playWhenReady == false && video.state.paused }
    }

    // ── The music queue ─────────────────────────────────────────────────────────────

    @Test fun aCallPausesTheMusicAndItCarriesOnWhenTheCallEnds() {
        playMusic()
        takeFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, call)
        await("paused for the call") { !audio.state.value.playing }
        giveFocusBack()
        await("playing again after the call") { audio.state.value.playing }
    }

    @Test fun anotherMediaAppPausesTheMusicForGood() {
        playMusic()
        takeFocus(AudioManager.AUDIOFOCUS_GAIN, media)
        await("paused for the other app") { !audio.state.value.playing }
        giveFocusBack()
        SystemClock.sleep(HOLD_MS)
        assertFalse("it does not start again by itself", audio.state.value.playing)
    }

    @Test fun headphonesGoingAwayPauseTheMusic() {
        assumeTrue("run by audio-noisy-check.sh", phase == "noisy")
        playMusic()
        awaitTheHeadphonesGoing()
        await("paused when the headphones went") { !audio.state.value.playing }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────────────

    private val call =
        attributes(PlatformAudioAttributes.USAGE_VOICE_COMMUNICATION, PlatformAudioAttributes.CONTENT_TYPE_SPEECH)
    private val prompt = attributes(
        PlatformAudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
        PlatformAudioAttributes.CONTENT_TYPE_SPEECH,
    )
    private val media = attributes(PlatformAudioAttributes.USAGE_MEDIA, PlatformAudioAttributes.CONTENT_TYPE_MUSIC)

    private fun attributes(usage: Int, content: Int) =
        PlatformAudioAttributes.Builder().setUsage(usage).setContentType(content).build()

    private fun playFilm() {
        main {
            video.load(
                NowPlaying(
                    target = PlaybackTarget(server.url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
                    title = "Clip",
                ),
            )
        }
        await("the film is playing") { video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) > 500 }
    }

    private fun playMusic() {
        main {
            audio.playQueue(listOf(AudioItem("track", "album", "Track", contentUrl = server.url, mime = "video/mp4")))
        }
        await("the queue is playing") { audio.state.value.playing && audio.state.value.positionMs >= 500 }
    }

    /** Another app's request, from this process: Android keeps a focus owner per request. */
    private fun takeFocus(gain: Int, attributes: PlatformAudioAttributes) {
        val request = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener {}
            .build()
        assertEquals("focus granted", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, audioManager.requestAudioFocus(request))
        taken = request
    }

    private fun giveFocusBack() {
        taken?.let { audioManager.abandonAudioFocusRequest(it) }
        taken = null
    }

    /** Tell the script to send the broadcast (it reads this log line), then let it arrive. */
    private fun awaitTheHeadphonesGoing() {
        Log.i(NOISY_TAG, NOISY_READY)
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
            val late = SystemClock.uptimeMillis() > deadline
            if (late) {
                throw AssertionError(
                    "timed out waiting: $what (film ${main {
                        video.state
                    }}, music ${audio.state.value})",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val NOISY_TAG = "HeyarrNoisy"
        const val NOISY_READY = "ready for the headphones to go"
        const val HOLD_MS = 3_000L
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
