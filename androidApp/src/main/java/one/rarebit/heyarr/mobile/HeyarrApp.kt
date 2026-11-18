package one.rarebit.heyarr.mobile

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Process
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
import one.rarebit.heyarr.mobile.login.LoginCoordinator
import one.rarebit.heyarr.mobile.login.LoginForegroundService
import one.rarebit.heyarr.mobile.login.PrefsPendingLoginStore
import one.rarebit.heyarr.mobile.login.QrLoginClient
import one.rarebit.heyarr.mobile.login.holdsProcess
import one.rarebit.heyarr.mobile.net.OkHttpTransport
import one.rarebit.heyarr.mobile.net.OkHttpVoidbindTransport
import one.rarebit.heyarr.mobile.net.RedirectNoStoreInterceptor

/**
 * The process-wide holders. The one that matters is [pairing]: the join → handshake
 * → confirm → enrol pipeline for a Cruciform invite runs here, on an app-wide scope,
 * so it survives the Activity, the Enrol screen leaving composition, and the app
 * being backgrounded while the user is over in Cruciform — and while it is live, a
 * foreground service ([PairingForegroundService]) keeps the process from being
 * killed. The Activity's ViewModel only observes it. [login] is the same for a QR login
 * handed to Cruciform on this phone (#172).
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

    /**
     * How many activities this process has created (heyarr-kmp#240). None, when an activity
     * is handed a saved state, means Android started this process to bring that activity back
     * after process death, with the shell under it not created (reader/ReaderStart).
     */
    @Volatile
    var activitiesCreated: Int = 0
        private set

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
     * The QR login and its `GET /login/{id}` poll, app-scoped for the same reason as
     * [pairing]: "Approve on this phone" puts the app behind Cruciform, where a
     * ViewModel-scoped poll died with a frozen or killed process (#172). The pending
     * login is persisted, so a restart resumes the poll.
     */
    val login: LoginCoordinator by lazy {
        LoginCoordinator(
            scope = appScope,
            store = PrefsPendingLoginStore(this),
            login = { base -> QrLoginClient(http = OkHttpTransport(), rpBase = base) },
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
        // The PDF viewer parses documents in androidx.pdf's isolatedProcess service, and
        // Android creates this Application there too. An isolated process has no storage,
        // so reading SharedPreferences (the pairing and login stores below) crashes it, and
        // no PDF ever loads (#162). Nothing app-wide belongs in that process.
        if (Process.isIsolated()) return
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                // Post-created: counted once onCreate has returned, so an activity reading the
                // count in its own onCreate sees only those created before it.
                override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
                    activitiesCreated++
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
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
        // And for as long as a QR login waits on Cruciform on this phone, or its approval
        // waits for the app to come back and apply it.
        appScope.launch {
            login.state
                .map { it.holdsProcess }
                .distinctUntilChanged()
                .collect { waiting ->
                    val intent = Intent(this@HeyarrApp, LoginForegroundService::class.java)
                    if (!waiting) {
                        stopService(intent)
                    } else {
                        // A login resumed after a process death can be read while the app
                        // is not yet in front, where Android refuses a foreground start; the
                        // resumed poll still runs, it is only not protected.
                        runCatching { startForegroundService(intent) }
                    }
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
