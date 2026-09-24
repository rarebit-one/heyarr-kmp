package one.rarebit.heyarr.mobile

import android.app.Application
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import one.rarebit.heyarr.mobile.consumption.DeviceIdStore
import one.rarebit.heyarr.mobile.consumption.PrefsDeviceIdStore
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.mobile.net.AuthHeaderSource
import one.rarebit.heyarr.mobile.net.AuthInterceptor
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.net.NsdMdnsResolver
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.playback.AudioPlayer
import one.rarebit.heyarr.mobile.playback.SessionAudioPlayer
import one.rarebit.heyarr.mobile.playback.VideoSession
import one.rarebit.heyarr.mobile.settings.PrefsSettingsStore
import one.rarebit.heyarr.mobile.settings.SettingsStore
import one.rarebit.heyarr.core.state.ExternalMetadata
import one.rarebit.heyarr.mobile.state.RecentSearches
import java.io.File
import java.util.concurrent.TimeUnit
import one.rarebit.heyarr.mobile.state.PhoneExternalMetadata

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

    /** This phone's node-issued device ids, for consumption sessions. */
    val deviceIds: DeviceIdStore = PrefsDeviceIdStore(app)

    /** The device-side personal-state role map (which space holds starred/history/reading). */
    internal val spaceRegistry: one.rarebit.heyarr.mobile.personalstate.SpaceRegistry =
        one.rarebit.heyarr.mobile.personalstate.PrefsSpaceRegistry(app)

    /** Swapped by the ViewModel as the credential changes; read per request. */
    val authHeader = AuthHeaderSource()

    /** The configured node, resolved fresh each time so a Settings change applies. */
    fun baseUrl(): String =
        HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride).baseUrl

    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AuthInterceptor(baseUrl = ::baseUrl, header = authHeader))
        .build()

    /** A bare client for the public metadata sources: no interceptor, so the node's credential never leaves for another host. */
    private val bareHttp: OkHttpClient = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    /** The raw transport over the shared client; the ViewModel wraps it for Device auth. */
    val rawTransport: HttpTransport = OkHttpTransport(okHttp)

    /** LAN mDNS browser (NsdManager) for auto-discovery; feeds `:core`'s NodeDiscovery chain. */
    val mdns: MdnsResolver = NsdMdnsResolver(app)

    /**
     * The audio queue, behind the seam: a MediaController bound to PlaybackService, so
     * music outlives the screen AND the Activity, with notification controls.
     */
    val audio: AudioPlayer by lazy { SessionAudioPlayer(app, scope) }

    /** The in-app video player, app-scoped so the now-playing bar can carry it between screens. */
    val video: VideoSession by lazy { VideoSession(app, okHttp, scope, liveAuthorization = authHeader::current) }

    /** Cover art and synopses from keyless public sources, cached under the app's cache dir (Settings → Appearance turns it off). */
    val external: ExternalMetadata by lazy {
        ExternalMetadata(File(app.cacheDir, "meta"), enabled = { settings.externalMetadata }, fetch = PhoneExternalMetadata.okHttpFetch(bareHttp))
    }

    /** Recent search queries — kept on this phone only, and labelled so. */
    val recent: RecentSearches by lazy { RecentSearches(File(app.filesDir, "recent-searches.json")) }
}
