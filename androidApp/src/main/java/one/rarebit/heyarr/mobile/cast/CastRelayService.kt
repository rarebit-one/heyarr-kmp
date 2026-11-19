package one.rarebit.heyarr.mobile.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.rarebit.heyarr.mobile.HeyarrApp
import one.rarebit.heyarr.mobile.MainActivity

/**
 * The [CastHold] the manager writes and [CastRelayService] reads. The service is started once,
 * when a relayed cast begins — the person has just pressed Play, so the app is in front, as a
 * `mediaPlayback` service start requires — and it follows [state] from then on: a pause past
 * the grace releases the locks but keeps the service, so a resume from the TV's remote while
 * the app is in the background never needs a second start the platform would refuse.
 */
class CastRelayHold(private val context: Context) : CastHold {
    private val _state = MutableStateFlow<CastHold.Hold?>(null)
    val state: StateFlow<CastHold.Hold?> = _state.asStateFlow()

    override fun update(hold: CastHold.Hold?) {
        val was = _state.value
        if (was == hold) return
        _state.value = hold
        if (was == null && hold != null) {
            runCatching { context.startForegroundService(Intent(context, CastRelayService::class.java)) }
        }
    }
}

/**
 * "Casting <film> to <TV>" — the foreground service that keeps this phone serving a relayed
 * film (heyarr-kmp#250). The phone is then the film's only path to the TV: if it sleeps or
 * drops off the network, the TV plays out its buffer and stops. So while the film plays (and
 * for [one.rarebit.heyarr.core.cast.CastTimeline.PAUSE_GRACE_SECONDS] after a pause) it holds
 * a partial wake lock, so the relay's threads keep running with the screen off, and a Wi-Fi
 * lock, so the radio does not doze between the receiver's reads.
 *
 * It does no work itself: the relay runs in [CastRelay]'s threads and the manager on the app
 * scope. `mediaPlayback` is the honest type: it is serving a film that is playing.
 */
// The app graph and MainActivity hold Media3's players; this reads the one and opens the other, touching neither.
@OptIn(UnstableApi::class)
class CastRelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val holds = (application as HeyarrApp).graph.castHold.state
        // startForeground first, always: a service started in the foreground that never calls
        // it within seconds takes the process down, even when there is nothing left to hold.
        startForeground(NOTIFICATION_ID, notification(holds.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (watch == null) {
            watch = scope.launch {
                holds.collect { hold ->
                    if (hold == null) {
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(hold))
                        locks(hold.locks)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    // Held for a film, so no timeout; released on a long pause, a stop or onDestroy, not at an onPause a service lacks.
    @Suppress("WakelockTimeout", "Wakelock", "DEPRECATION")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heyarr:cast-relay")
                    .apply { acquire() }
            }
            if (wifi == null) {
                // FULL_HIGH_PERF is deprecated on 34+ in favour of LOW_LATENCY, which only holds
                // while the app is in front: the opposite of a phone left on a table, screen off.
                wifi = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "heyarr:cast-relay")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }
        } else {
            wake?.takeIf { it.isHeld }?.release()
            wake = null
            wifi?.takeIf { it.isHeld }?.release()
            wifi = null
        }
    }

    private fun notification(hold: CastHold.Hold?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone relays a film to a TV."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = hold?.let { "Casting ${it.title} to ${it.device}" } ?: "Casting"
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("This phone is sending the film to the TV. Keep it on the same network.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        locks(false)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "cast-relay"
        const val NOTIFICATION_ID = 0xCA57
    }
}
