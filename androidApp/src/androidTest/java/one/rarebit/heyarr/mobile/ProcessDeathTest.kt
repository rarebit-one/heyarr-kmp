package one.rarebit.heyarr.mobile

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.mobile.playback.AudioItem
import one.rarebit.heyarr.mobile.playback.AudioQueueSnapshot
import one.rarebit.heyarr.mobile.playback.NowPlaying
import one.rarebit.heyarr.mobile.playback.PlaybackTarget
import one.rarebit.heyarr.mobile.playback.PrefsAudioQueueStore
import one.rarebit.heyarr.mobile.playback.PrefsPlayerSnapshotStore
import one.rarebit.heyarr.mobile.playback.VideoSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The player after process death (heyarr-kmp#232), across two processes. An instrumented test
 * runs inside the app's process, so the death has to happen between two runs:
 * `androidApp/scripts/process-death-check.sh` runs [leaveAFilmPlaying] (phase `leave`), the
 * process ends with that instrumentation as a kill in the background ends it (no Stop, nothing
 * after the activity's stop), and then [restoreItPaused] (phase `restore`) in a fresh process.
 * Both go through the app's own [AppGraph], so the writing is the app's wiring, not the test's.
 *
 * Skipped in the ordinary connected run, where no phase is given.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val graph get() = (ctx.applicationContext as HeyarrApp).graph
    private val phase: String? = InstrumentationRegistry.getArguments().getString("phase")
    private val clip = File(ctx.cacheDir, "smoke/death-clip.mp4")
    private val snapshotFile = File(ctx.dataDir, "shared_prefs/${PrefsPlayerSnapshotStore.PREFS_NAME}.xml")
    private val musicClip = File(ctx.cacheDir, "smoke/music-clip.mp4")

    @Test fun leaveAFilmPlaying() {
        assumeTrue("run by process-death-check.sh", phase == "leave")
        TestClip.write(clip, seconds = CLIP_SECONDS, width = 160, height = 120, fps = 10)
        val server = ClipServer(clip.readBytes())
        val video = main { graph.video }
        val scenario = show(video)
        main { video.load(nowPlaying(server.url)) }
        await(video, "playing") { video.player?.isPlaying == true }
        main { video.seekTo(LEFT_AT_MS) }
        await(video, "playing past where it was sought") {
            video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= LEFT_AT_MS + 1_000
        }
        var task = 0
        scenario.onActivity { task = it.taskId }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await(video, "released in the background") { video.player == null }
        val onDisk = snapshotOnDisk()
        assertNotNull("what the player held reached the disk before the stop completed", onDisk)
        assertEquals(ASSET, onDisk!!.assetId)
        assertEquals(WORK, onDisk.workId)
        assertEquals("the blob, for the plan after the restart", HASH, onDisk.item.blobHash)
        assertEquals((LEFT_AT_MS + 1_500) / 1000.0, onDisk.positionSeconds, 1.5)
        // No Stop and no tidying: the process ends with this instrumentation, behind other apps.
        check(task != 0)
    }

    @Test fun restoreItPaused() {
        assumeTrue("run by process-death-check.sh", phase == "restore")
        // What AppGraph read as this process started: the film, where it was.
        val point = main { graph.takeRestore() }
        assertNotNull("the snapshot survived the process", point)
        assertEquals(ASSET, point!!.assetId)
        assertEquals(WORK, point.workId)
        assertEquals((LEFT_AT_MS + 1_500) / 1000.0, point.positionSeconds, 1.5)
        assertEquals(
            "and the resume point, for Detail and Continue watching",
            point.positionSeconds,
            main { graph.resumePoints.get(ASSET)?.positionSeconds } ?: -1.0,
            1.0,
        )

        // Loaded as PlaybackCoordinator.restore presents it (unit-tested): paused, at the point.
        val server = ClipServer(clip.readBytes())
        val video = main { graph.video }
        show(video)
        main { video.load(nowPlaying(server.url).copy(startSeconds = point.positionSeconds, startPaused = true)) }
        val atMs = (point.positionSeconds * 1000).toLong()
        await(video, "ready where it was") {
            video.player?.playbackState == Player.STATE_READY && (video.player?.currentPosition ?: 0) >= atMs - SLACK_MS
        }
        SystemClock.sleep(STAYS_PAUSED_MS)
        assertFalse("paused: a restore does not start playing by itself", main { video.player!!.isPlaying })
        assertEquals("still where it was", atMs.toDouble(), main { video.player!!.currentPosition }.toDouble(), 1_500.0)

        main { video.play() }
        await(video, "Play carries on from there") {
            video.player?.isPlaying == true && (video.player?.currentPosition ?: 0) >= atMs + 1_000
        }
        main { video.stop() }
        assertEquals("Stop: nothing left to restore", null, main { PrefsPlayerSnapshotStore(ctx).read() })
        server.close()
    }

    // ── The music queue (item 5) ────────────────────────────────────────────────────

    @Test fun leaveMusicPlaying() {
        assumeTrue("run by process-death-check.sh", phase == "leave-music")
        TestClip.write(musicClip)
        val server = ClipServer(musicClip.readBytes())
        val audio = main { graph.audio }
        // An activity that writes the queue down as it stops, as MainActivity does.
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) graph.audio.checkpoint() },
            )
        }
        main { audio.playQueue(listOf(track("t1", server.url), track("t2", server.url))) }
        awaitAudio("the queue is playing") { audio.state.value.playing }
        main { audio.seekTo(MUSIC_AT_MS) }
        awaitAudio("playing past where it was sought") {
            audio.state.value.playing && audio.state.value.positionMs >= MUSIC_AT_MS + 1_000
        }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        // ActivityScenario's state is read off the main thread.
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (scenario.state != Lifecycle.State.CREATED) {
            if (SystemClock.uptimeMillis() > deadline) throw AssertionError("never went to the background")
            SystemClock.sleep(POLL_MS)
        }
        val onDisk = queueOnDisk()
        assertNotNull("the queue reached the disk as the app went to the background", onDisk)
        assertEquals(listOf("t1", "t2"), onDisk!!.items.map { it.assetId })
        assertEquals(0, onDisk.index)
        assertEquals((MUSIC_AT_MS + 1_500).toDouble(), onDisk.positionMs.toDouble(), 2_000.0)
        // Still playing in the background; the process ends with this instrumentation.
        assertTrue(audio.state.value.playing)
    }

    @Test fun restoreTheQueuePaused() {
        assumeTrue("run by process-death-check.sh", phase == "restore-music")
        assertEquals("no film was left", null, main { graph.takeRestore() })
        val queue = main { graph.takeAudioRestore() }
        assertNotNull("the queue survived the process", queue)
        assertEquals(listOf("t1", "t2"), queue!!.items.map { it.assetId })
        assertEquals((MUSIC_AT_MS + 1_500).toDouble(), queue.positionMs.toDouble(), 2_000.0)

        // Put back as the shell puts it back: paused, on its track, at its place. The content URL
        // it kept points at the last process's server, so the same queue is served again.
        val server = ClipServer(musicClip.readBytes())
        val audio = main { graph.audio }
        val items = queue.items.map { it.copy(contentUrl = server.url) }
        main { audio.restoreQueue(items, queue.index, queue.positionMs) }
        awaitAudio("back in the bar") { audio.state.value.item?.assetId == "t1" }
        SystemClock.sleep(STAYS_PAUSED_MS)
        assertFalse("paused: a restore does not start playing by itself", audio.state.value.playing)
        main { audio.play() }
        awaitAudio("Play carries on from where it was") {
            audio.state.value.playing && audio.state.value.positionMs >= queue.positionMs + 1_000
        }
        main { audio.stop() }
        // Through the store, as for the film: apply() writes the file a moment later.
        assertEquals("Stop: nothing left to restore", null, main { PrefsAudioQueueStore(ctx).read() })
        server.close()
    }

    private fun track(id: String, url: String) =
        AudioItem(id, "album", "Track $id", contentUrl = url, mime = "video/mp4")

    private fun queueOnDisk(): AudioQueueSnapshot? {
        val xml = if (snapshotFile.exists()) snapshotFile.readText() else ""
        val start = xml.indexOf("name=\"audio\">")
        if (start < 0) return null
        val body = xml.substring(xml.indexOf('>', start) + 1, xml.indexOf("</string>", start))
        return AudioQueueSnapshot.fromJson(body.replace("&quot;", "\"").replace("&amp;", "&"))
    }

    private fun awaitAudio(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!main(condition)) {
            val late = SystemClock.uptimeMillis() > deadline
            if (late) throw AssertionError("timed out waiting: $what (${main { graph.audio.state.value }})")
            SystemClock.sleep(POLL_MS)
        }
    }

    private fun nowPlaying(url: String) = NowPlaying(
        target = PlaybackTarget(url, Credential.Guest, isVideo = true, mimeType = "video/mp4"),
        title = "Clip",
        assetId = ASSET,
        blobHash = HASH,
        workId = WORK,
        kind = "movie",
    )

    /** An activity wired as MainActivity wires it, showing the player as the player screen does. */
    private fun show(video: VideoSession): ActivityScenario<ComponentActivity> {
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
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
        return scenario
    }

    private fun snapshotOnDisk(): ResumePoint? {
        val xml = if (snapshotFile.exists()) snapshotFile.readText() else ""
        val start = xml.indexOf("name=\"video\">")
        if (start < 0) return null
        val body = xml.substring(xml.indexOf('>', start) + 1, xml.indexOf("</string>", start))
        return ResumePoint.fromJson(body.replace("&quot;", "\"").replace("&amp;", "&"))
    }

    private fun <T> main(read: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = read() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(video: VideoSession, what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!main(condition)) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw AssertionError("timed out waiting: $what (state ${main { video.state }})")
            }
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val ASSET = "death-asset"
        const val WORK = "death-work"
        const val HASH = "blake3:death"
        const val CLIP_SECONDS = 120
        const val LEFT_AT_MS = 45_000L
        const val MUSIC_AT_MS = 20_000L
        const val SLACK_MS = 1_000L
        const val STAYS_PAUSED_MS = 2_000L
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
