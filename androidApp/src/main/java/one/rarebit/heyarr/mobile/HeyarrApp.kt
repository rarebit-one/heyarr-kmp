package one.rarebit.heyarr.mobile

import android.app.Application
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
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
     * `respectCacheHeaders(false)` because the artwork redirect's short private max-age
     * would otherwise defeat the disk cache on every scroll — the blob target is
     * immutable by hash, and a changed poster is a changed URL.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { graph.okHttp }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()

    override fun onCreate() {
        super.onCreate()
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
}
