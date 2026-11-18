package one.rarebit.heyarr.mobile

import android.app.UiAutomation
import android.os.SystemClock
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
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.core.playback.ResumePoints
import one.rarebit.heyarr.core.playback.ResumeRecorder
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackProgress
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.PrefsResumeStore
import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The resume position on a device (heyarr-kmp#232). A real clip plays through [VideoSession],
 * reporting to the same [ResumeRecorder] over the same [PrefsResumeStore] `AppGraph` wires,
 * and the point is read back from SharedPreferences' FILE, not its in-memory cache: what
 * survives the process being killed in the background is what reached the disk.
 *
 * What it cannot show: the process actually being killed and the app relaunched. Instrumented
 * tests run inside the app's process, so killing it ends the test; the restore after process
 * death is the next change's, with a check run from outside the process.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ResumePositionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val clip = File(ctx.cacheDir, "smoke/long-clip.mp4")
    private val prefsFile = File(ctx.dataDir, "shared_prefs/${PrefsResumeStore.PREFS_NAME}.xml")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var server: ClipServer
    private lateinit var store: PrefsResumeStore
    private lateinit var video: VideoSession
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        ctx.getSharedPreferences(PrefsResumeStore.PREFS_NAME, 0).edit().clear().commit()
        // Two minutes, small: room past the half-minute a point needs, and short of the credits.
        if (!clip.exists()) TestClip.write(clip, seconds = CLIP_SECONDS, width = 160, height = 120, fps = 10)
        server = ClipServer(clip.readBytes())
        instrumentation.runOnMainSync {
            store = PrefsResumeStore(ctx)
            val recorder = ResumeRecorder(store, clock = System::currentTimeMillis)
            video = VideoSession(ctx, OkHttpClient(), scope).also { v ->
                // As AppGraph.recordResume wires it.
                v.onProgress = { p ->
                    val item = v.current!!.resumeItem()!!
                    when (p.event) {
                        PlaybackProgress.Event.TICK -> recorder.progress(item, p.seconds, v.durationSeconds())
                        PlaybackProgress.Event.ENDED -> recorder.ended(item)
                        PlaybackProgress.Event.RESUMED -> Unit
                        else -> recorder.checkpoint(item, p.seconds, v.durationSeconds())
                    }
                }
            }
        }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(video.lifecycleObserver { activity.isChangingConfigurations })
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
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        instrumentation.runOnMainSync { video.stop() }
        scenario.close()
        server.close()
        scope.cancel()
    }

    private fun load(startSeconds: Double = 0.0) = main {
        video.load(
            NowPlaying(
                target = PlaybackTarget(server.url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
                title = "Clip",
                assetId = ASSET,
                workId = WORK,
                kind = "movie",
                startSeconds = startSeconds,
            ),
        )
    }

    @Test fun aPausedFilmLeftForTheBackgroundIsOnDiskAndPlayResumesThere() {
        load()
        awaitPlayingPast(1_000)
        main { video.seekTo(40_000) }
        awaitPlayingPast(41_000)
        main { video.pause() }
        await("the pause wrote the point") { (store.get(ASSET)?.positionSeconds ?: 0.0) >= 41.0 }

        // Sought while paused, then HOME: the background writes where it now stands.
        main { video.seekTo(PAUSED_AT_MS) }
        await("the paused seek landed") { (video.player?.currentPosition ?: 0) == PAUSED_AT_MS }
        var task = 0
        scenario.onActivity { task = it.taskId }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("released in the background") { video.player == null }
        val onDisk = pointOnDisk()
        assertNotNull("the point reached the disk before the stop completed", onDisk)
        assertEquals("where the paused film stood", PAUSED_AT_MS / 1000.0, onDisk!!.positionSeconds, 0.5)
        assertEquals(WORK, onDisk.workId)
        assertTrue("with the runtime, for the progress bar", (onDisk.durationSeconds ?: 0.0) > CLIP_SECONDS - 2)

        TestClip.backFromRecents(instrumentation, scenario, task)
        await("back, paused, where it was") { video.player != null && video.state.paused }

        // Stop, then Play with no position: the coordinator's lookup, then the player.
        main { video.stop() }
        val start = main { ResumePoints.startFor(store, ASSET) }
        assertEquals("Play resumes by default", PAUSED_AT_MS / 1000.0, start!!, 0.5)
        load(start)
        main { video.play() }
        awaitPlayingPast(PAUSED_AT_MS)
        assertTrue(
            "resumed from the point, not 0:00",
            main { video.player!!.currentPosition } in PAUSED_AT_MS - SLACK_MS..PAUSED_AT_MS + PLAY_WINDOW_MS,
        )
    }

    @Test fun aRotationKeepsTheSamePlayerPlayingAndThePointMoving() {
        load(startSeconds = 40.0)
        awaitPlayingPast(41_000)
        val before = main { video.player }
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_90)
        SystemClock.sleep(ROTATION_SETTLE_MS)
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0)
        SystemClock.sleep(ROTATION_SETTLE_MS)
        assertSame("a rotation is not a stop: no rebuild, no rebuffer", before, main { video.player })
        assertTrue("still playing", main { video.player!!.isPlaying })
        awaitPlayingPast(52_000)
        await("ticks keep writing it") { (store.get(ASSET)?.positionSeconds ?: 0.0) >= 50.0 }
    }

    @Test fun startingOverAndPlayingToTheEndLeaveNoPoint() {
        load(startSeconds = 40.0)
        awaitPlayingPast(41_000)
        main { video.pause() }
        await("a point") { store.get(ASSET) != null }
        main { video.seekTo(5_000) }
        await("sought back") { (video.player?.currentPosition ?: Long.MAX_VALUE) < 6_000 }
        main { video.stop() }
        assertNull("stopped in the first half-minute: Start over next time", main { store.get(ASSET) })

        load(startSeconds = CLIP_SECONDS - 3.0)
        await("played to its end") { video.state.ended }
        assertNull("watched: off the Continue watching row", main { store.get(ASSET) })
        assertNull(pointOnDisk())
    }

    /** The point as SharedPreferences wrote it to its file; null when the file holds none. */
    private fun pointOnDisk(): ResumePoint? {
        val xml = if (prefsFile.exists()) prefsFile.readText() else ""
        val start = xml.indexOf("name=\"asset.$ASSET\">")
        if (start < 0) return null
        val body = xml.substring(xml.indexOf('>', start) + 1, xml.indexOf("</string>", start))
        return ResumePoint.fromJson(body.replace("&quot;", "\"").replace("&amp;", "&"))
    }

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
        const val ASSET = "clip-asset"
        const val WORK = "clip-work"
        const val CLIP_SECONDS = 120
        const val PAUSED_AT_MS = 63_000L
        const val SLACK_MS = 1_000L
        const val PLAY_WINDOW_MS = 5_000L
        const val ROTATION_SETTLE_MS = 2_000L
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
