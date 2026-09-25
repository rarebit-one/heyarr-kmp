package one.rarebit.heyarr.mobile.playback

import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
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
        val okHttp = (application as HeyarrApp).graph.okHttp
        val dataSources = OkHttpDataSource.Factory(okHttp)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
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
