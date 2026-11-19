package one.rarebit.heyarr.mobile.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.LaunchOptions
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.MediaIntentReceiver
import com.google.android.gms.cast.framework.media.NotificationOptions
import one.rarebit.heyarr.mobile.MainActivity

/**
 * The Cast framework's configuration (heyarr-kmp#250), named in the manifest.
 *
 *  - **The Default Media Receiver.** heyarr registers no receiver app of its own: the stock
 *    receiver plays a progressive MP4 URL, which is all the node's Cast-profile stream is.
 *  - **Relaunch if running.** Joining a media app that is already up is how a wedged one was
 *    reused (heyarr-kmp#248), so every new session launches it afresh, and ending one stops it.
 *  - **The framework's notification and media session** are the lock-screen and notification
 *    controls and carry the volume keys. Their scrubber is the receiver's clock, which a
 *    restart seek resets; a seek made there is followed by [CastManager] like the TV remote's.
 *  - **Reconnection.** The SDK resumes a session after a short drop (and after the app
 *    restarts); [CastManager] rejoins one it gave up on.
 */
class CastOptionsProvider : OptionsProvider {
    @OptIn(UnstableApi::class) // Names MainActivity, which holds Media3's players; nothing here touches them.
    override fun getCastOptions(context: Context): CastOptions {
        val notification = NotificationOptions.Builder()
            .setActions(
                listOf(MediaIntentReceiver.ACTION_TOGGLE_PLAYBACK, MediaIntentReceiver.ACTION_STOP_CASTING),
                intArrayOf(0, 1),
            )
            .setTargetActivityClassName(MainActivity::class.java.name)
            .build()
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(notification)
            .setMediaSessionEnabled(true)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setLaunchOptions(LaunchOptions.Builder().setRelaunchIfRunning(true).build())
            .setStopReceiverApplicationWhenEndingSession(true)
            .setResumeSavedSession(true)
            .setEnableReconnectionService(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
