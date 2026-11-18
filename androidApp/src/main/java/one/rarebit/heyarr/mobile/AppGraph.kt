package one.rarebit.heyarr.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.playback.PlayerRestore
import one.rarebit.heyarr.core.playback.ResumePoint
import one.rarebit.heyarr.core.playback.ResumeRecorder
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.AuthInterceptor
import one.rarebit.heyarr.mobile.net.NsdMdnsResolver
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.AudioQueueSnapshot
import one.rarebit.heyarr.mobile.playback.PlaybackProgress
import one.rarebit.heyarr.mobile.playback.PrefsAudioQueueStore
import one.rarebit.heyarr.mobile.playback.PrefsPlayerSnapshotStore
import one.rarebit.heyarr.mobile.playback.PrefsResumeStore
import one.rarebit.heyarr.mobile.playback.RenderCapabilityCache
import one.rarebit.heyarr.mobile.playback.SessionAudioPlayer
import one.rarebit.heyarr.mobile.playback.VideoSession
import one.rarebit.heyarr.mobile.settings.PrefsSettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.heyarr.mobile.state.PhoneExternalMetadata
import one.rarebit.heyarr.mobile.state.RecentSearches
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The process-wide object graph — by hand, on purpose. A container would add a
 * compiler plugin and a second way of building objects for a single module with a
 * handful of ViewModels, and the one genuinely awkward binding (a credential that swaps
 * shape mid-session) is already solved by keying the session holder on the credential
 * class.
 *
 * What lives here is what must be ONE instance: the OkHttp client (connection pool,
 * and the [AuthInterceptor] that lets posters and range reads carry the credential
 * without ever seeing it), the settings store, the live auth header source, the two
 * players (the audio queue's service controller and the in-app video session), the
 * public-metadata cache and the recent-searches file.
 */
@UnstableApi
class AppGraph(app: Application, scope: CoroutineScope) {

    val settings: SettingsStore = PrefsSettingsStore(app)

    /** The device-side personal-state role map (which space holds starred/playlists). */
    internal val spaceRegistry: one.rarebit.heyarr.mobile.personalstate.SpaceRegistry =
        one.rarebit.heyarr.mobile.personalstate.PrefsSpaceRegistry(app)

    /** Swapped by the ViewModel as the credential changes; read per request. */
    val authHeader = AuthHeaderSource()

    /** The configured node, resolved fresh each time so a Settings change applies. */
    fun baseUrl(): String = HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride).baseUrl

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(baseUrl = ::baseUrl, header = authHeader))
        .build()

    /**
     * The players' client (heyarr-kmp#232): the same pool, interceptor and credential, with no
     * overall call timeout. A film or a long track is ONE ranged read that streams for as long
     * as the player keeps loading, and the 30 s call timeout above cut every one of them off
     * half a minute in: a needless reconnect at best, and during an outage one more failure
     * on the count. Connect and read timeouts still catch a node that has gone quiet.
     */
    val streamHttp: OkHttpClient = okHttp.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()

    /** A bare client for the public metadata sources: no interceptor, so the node's credential never leaves for another host. */
    private val bareHttp: OkHttpClient = OkHttpClient.Builder().callTimeout(
        20,
        TimeUnit.SECONDS,
    ).followRedirects(true).build()

    /** The raw transport over the shared client; the ViewModel wraps it for Device auth. */
    val rawTransport: HttpTransport = OkHttpTransport(okHttp)

    /** LAN mDNS browser (NsdManager) for auto-discovery; feeds `:core`'s NodeDiscovery chain. */
    val mdns: MdnsResolver = NsdMdnsResolver(app)

    /**
     * The audio queue, behind the seam: a MediaController bound to PlaybackService, so
     * music outlives the screen AND the Activity, with notification controls.
     */
    val audio: AudioPlayer by lazy { SessionAudioPlayer(app, scope, renderCapabilities, audioQueue) }

    /** The music queue as it stood when the app last left it, for the restore after process death. */
    private val audioQueue = PrefsAudioQueueStore(app)

    /** What queue a process that died left (heyarr-kmp#232), handed over once, like [takeRestore]. */
    private var audioRestore: AudioQueueSnapshot? =
        audioQueue.read()?.takeIf { PlayerRestore.fresh(it.updatedAtMs, System.currentTimeMillis()) }

    fun takeAudioRestore(): AudioQueueSnapshot? = audioRestore.also { audioRestore = null }

    /**
     * Render capabilities for the audio queue (heyarr-core ADR-0112), minted when a queue
     * starts and swapped in by PlaybackService as each track loads, so a locked phone —
     * which cannot sign a proof — keeps streaming. The session sets its minter.
     */
    val renderCapabilities = RenderCapabilityCache()

    /** Where each item was left on this phone (heyarr-kmp#232): Play resumes there, Home lists them. */
    val resumePoints: PrefsResumeStore = PrefsResumeStore(app)

    /** What the in-app player held when the app last left it: put back, paused, after process death. */
    private val playerSnapshots = PrefsPlayerSnapshotStore(app)

    private val resumeRecorder = ResumeRecorder(resumePoints, playerSnapshots, System::currentTimeMillis)

    /**
     * What a process that died was in the middle of (heyarr-kmp#232), read once as this one
     * starts. [takeRestore] hands it over once: a rotation or a second activity must not put it
     * back again over whatever has been played since.
     */
    private var restore: ResumePoint? =
        playerSnapshots.read()?.takeIf { PlayerRestore.restorable(it, System.currentTimeMillis()) }

    fun takeRestore(): ResumePoint? = restore.also { restore = null }

    /**
     * The in-app video player, app-scoped so the now-playing bar can carry it between screens.
     * Its position is written here rather than by a screen, so it is recorded whatever is on
     * screen, and in the background, where no screen is.
     */
    val video: VideoSession by lazy {
        VideoSession(app, streamHttp, scope, liveAuthorization = authHeader::current).also { v ->
            v.onProgress = { p -> recordResume(v, p) }
            v.onStopped = resumeRecorder::stopped
        }
    }

    private fun recordResume(video: VideoSession, progress: PlaybackProgress) {
        val item = video.current?.resumeItem() ?: return
        val duration = video.durationSeconds() ?: video.current?.target?.sourceDurationSeconds
        when (progress.event) {
            PlaybackProgress.Event.TICK -> resumeRecorder.progress(item, progress.seconds, duration)

            PlaybackProgress.Event.ENDED -> resumeRecorder.ended(item)

            PlaybackProgress.Event.RESUMED -> Unit

            PlaybackProgress.Event.PAUSED,
            PlaybackProgress.Event.LEFT,
            PlaybackProgress.Event.CHECKPOINT,
            -> resumeRecorder.checkpoint(item, progress.seconds, duration)
        }
    }

    /** Cover art and synopses from keyless public sources, cached under the app's cache dir (Settings → Appearance turns it off). */
    val external: ExternalMetadata by lazy {
        ExternalMetadata(File(app.cacheDir, "meta"), enabled = {
            settings.externalMetadata
        }, fetch = PhoneExternalMetadata.okHttpFetch(bareHttp))
    }

    /** Recent search queries — kept on this phone only, and labelled so. */
    val recent: RecentSearches by lazy { RecentSearches(File(app.filesDir, "recent-searches.json")) }
}
