package one.rarebit.heyarr.mobile.playback

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.MoreExecutors
import one.rarebit.heyarr.mobile.HeyarrApp
import java.util.concurrent.Executors

/**
 * The audio queue's home: one ExoPlayer under a MediaSession, so music survives the
 * Activity, shows notification / lock-screen controls, and answers the system's media
 * buttons. Every read — the tracks and the notification artwork alike — goes through the
 * shared OkHttp client, whose interceptor stamps the live credential.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as HeyarrApp).graph
        val okHttp = graph.okHttp
        val dataSources = OkHttpDataSource.Factory(okHttp)
        // Each track's blob URL is swapped, as it loads, for the render capability minted
        // when the queue started — a read that needs no proof, so it works on a locked
        // phone (RenderCapabilityCache). No capability: the blob URL, on a proof, as before.
        val trackSources = ResolvingDataSource.Factory(dataSources) { spec ->
            graph.renderCapabilities.resolve(spec.uri.toString())?.let { spec.withUri(Uri.parse(it)) } ?: spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(trackSources).setLoadErrorHandlingPolicy(LockedPlayback.ErrorPolicy()),
            )
            // Load far ahead while the phone can still sign range reads, and wait out a
            // locked phone rather than fail (LockedPlayback): music under the lock screen
            // used to stop about two minutes in.
            .setLoadControl(LockedPlayback.loadControl())
            // A partial wake lock and a Wi-Fi lock while playing: the queue streams, and
            // Wi-Fi power save under the lock screen otherwise slows or stalls the reads.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            // Declare MUSIC audio + take audio focus, standard for a media
            // session (ducking/pausing for calls). This is correct hygiene the
            // default UNKNOWN attributes skip; the video path declares MOVIE so
            // the system does not treat it as music (issue #42).
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .build()
        session = MediaSession.Builder(this, player)
            .setBitmapLoader(
                DataSourceBitmapLoader(
                    MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                    dataSources,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app was swiped away: keep playing if playing, else go quietly. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
