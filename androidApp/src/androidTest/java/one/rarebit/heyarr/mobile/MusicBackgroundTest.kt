package one.rarebit.heyarr.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.rarebit.heyarr.mobile.playback.AudioItem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Music in the background (heyarr-kmp#232): unlike a film, which pauses and lets go of its
 * decoder (#231), the audio queue keeps playing when the app is sent away, under
 * `PlaybackService`'s MediaSession with its media notification (the lock-screen and
 * notification-shade controls). Played through the app's own graph, as the screens play it.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class MusicBackgroundTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val ctx = instrumentation.targetContext
    private val audio get() = (ctx.applicationContext as HeyarrApp).graph.audio
    private val clip = File(ctx.cacheDir, "smoke/clip.mp4")
    private lateinit var server: ClipServer
    private lateinit var scenario: ActivityScenario<ComponentActivity>

    @Before fun setUp() {
        // Media notifications are exempt from the permission on 13+, but grant it so the test
        // does not lean on that.
        instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (!clip.exists()) TestClip.write(clip)
        server = ClipServer(clip.readBytes())
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { audio.stop() }
        scenario.close()
        server.close()
    }

    @Test fun theQueuePlaysOnInTheBackgroundUnderAMediaNotification() {
        instrumentation.runOnMainSync {
            audio.playQueue(listOf(AudioItem("track", "album", "Track", contentUrl = server.url, mime = "video/mp4")))
        }
        await("the queue is playing") { audio.state.value.playing && audio.state.value.positionMs >= 1_000 }
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        await("stopped in the background") { scenario.state == androidx.lifecycle.Lifecycle.State.CREATED }
        val left = audio.state.value.positionMs
        SystemClock.sleep(BACKGROUND_MS)
        val now = audio.state.value
        assertTrue("still playing behind other apps", now.playing)
        assertTrue("and moving: ${now.positionMs} after $left", now.positionMs >= left + BACKGROUND_MS - SLACK_MS)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        assertTrue(
            "a media-session notification carries the controls",
            nm.activeNotifications.any { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) },
        )
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (!condition()) {
            val late = SystemClock.uptimeMillis() > deadline
            if (late) throw AssertionError("timed out waiting: $what (${audio.state.value})")
            SystemClock.sleep(POLL_MS)
        }
    }

    private companion object {
        const val BACKGROUND_MS = 5_000L
        const val SLACK_MS = 1_500L
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
    }
}
