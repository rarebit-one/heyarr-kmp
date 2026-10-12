package one.rarebit.heyarr.mobile

import android.app.Application
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.device.CruciformAnnouncer
import one.rarebit.heyarr.mobile.device.CruciformPairCallback
import one.rarebit.heyarr.mobile.device.DeviceKeyring
import one.rarebit.heyarr.mobile.device.DevicePairingSteps
import one.rarebit.heyarr.mobile.device.HandoffLauncher
import one.rarebit.heyarr.mobile.device.PairingCoordinator
import one.rarebit.heyarr.mobile.device.PairingForegroundService
import one.rarebit.heyarr.mobile.device.PairingState
import one.rarebit.heyarr.mobile.device.PrefsPendingPairingStore
import one.rarebit.heyarr.mobile.net.OkHttpVoidbindTransport
import one.rarebit.heyarr.mobile.net.RedirectNoStoreInterceptor

/**
 * The process-wide holders. The one that matters is [pairing]: the join → handshake
 * → confirm → enrol pipeline for a Cruciform invite runs here, on an app-wide scope,
 * so it survives the Activity, the Enrol screen leaving composition, and the app
 * being backgrounded while the user is over in Cruciform — and while it is live, a
 * foreground service ([PairingForegroundService]) keeps the process from being
 * killed. The Activity's ViewModel only observes it.
 */
@UnstableApi
class HeyarrApp :
    Application(),
    ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** The process-wide object graph: settings, the shared OkHttp client, the auth header source. */
    val graph: AppGraph by lazy { AppGraph(this, appScope) }

    /**
     * The device keyring of the Activity currently in front (its biometric prompt binds
     * to that Activity). Set by [MainActivity] on create; read per pairing step.
     */
    @Volatile
    var deviceKeyring: DeviceKeyring? = null

    /** The signed-in credential, if any, offered to the admin-registration lane of `/enrol`. */
    @Volatile
    var credentialProvider: () -> one.rarebit.heyarr.core.auth.Credential? = { null }

    /** What to call this phone in the node's device registry. */
    @Volatile
    var deviceName: String = "heyarr-mobile"

    val pairing: PairingCoordinator by lazy {
        val settings = graph.settings
        PairingCoordinator(
            scope = appScope,
            store = PrefsPendingPairingStore(this),
            steps = {
                DevicePairingSteps(
                    keyring = { deviceKeyring },
                    relayTransport = OkHttpVoidbindTransport(),
                    nodeTransport = graph.rawTransport,
                    baseUrl = {
                        HeyarrConfig.resolve(settings.baseUrlOverride, settings.qualityProfileOverride).baseUrl
                    },
                    deviceName = { deviceName },
                    credential = { credentialProvider() },
                )
            },
            // The same-phone one-tap channel (voidbind-kmp ADR-0008): tell Cruciform on
            // THIS phone what we derived, so the two apps settle the SAS comparison the
            // human was doing across two screens. Nothing takes it → false → this phone
            // shows the SAS and the human compares, exactly as before.
            announcer = CruciformAnnouncer { session, deviceId, sas ->
                HandoffLauncher.open(this, CruciformPairCallback.joinedUri(session, deviceId, sas))
            },
        )
    }

    /**
     * Coil's loader over the shared OkHttp client: a poster URL on our node picks up the
     * live credential in net/AuthInterceptor; one on any other host goes out bare.
     *
     * Cache headers are respected. Coil sees only the response at the end of the
     * artwork redirect — a blob, `immutable` by hash — so the redirect's short private
     * max-age never reaches it and a card is still cached for good. The exception is a
     * thumbnail still being derived: the redirect to the original says `no-store`, and
     * [RedirectNoStoreInterceptor] carries that onto the original so it is not filed
     * under the sized URL in place of the thumbnail (#140).
     *
     * The directory is versioned because the previous loader ignored `no-store` and
     * pinned full-size originals under sized URLs; those entries would never be
     * replaced, so the old directory is dropped once ([dropStaleImageCache]).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.okHttp.newBuilder().addInterceptor(RedirectNoStoreInterceptor()).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve(IMAGE_CACHE_DIR))
                .maxSizePercent(IMAGE_CACHE_MAX_PERCENT)
                .build()
        }
        .crossfade(true)
        .build()

    /** Removes the pre-#140 image cache directory; a no-op once it is gone. */
    private fun dropStaleImageCache() {
        appScope.launch(Dispatchers.IO) { cacheDir.resolve(STALE_IMAGE_CACHE_DIR).deleteRecursively() }
    }

    override fun onCreate() {
        super.onCreate()
        dropStaleImageCache()
        // Keep a foreground service up for exactly as long as a pairing is in flight.
        appScope.launch {
            pairing.state
                .map { it is PairingState.Live || it is PairingState.Registering }
                .distinctUntilChanged()
                .collect { inFlight ->
                    val intent = Intent(this@HeyarrApp, PairingForegroundService::class.java)
                    if (inFlight) startForegroundService(intent) else stopService(intent)
                }
        }
    }

    private companion object {
        /** Coil's default directory name, used by the loader before #140. */
        const val STALE_IMAGE_CACHE_DIR = "image_cache"
        const val IMAGE_CACHE_DIR = "image_cache_v2"

        /** Coil's default share of free disk space. */
        const val IMAGE_CACHE_MAX_PERCENT = 0.02
    }
}
