package one.rarebit.heyarr.mobile.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import one.rarebit.heyarr.mobile.HeyarrApp
import one.rarebit.heyarr.mobile.MainActivity

/**
 * "Pairing with Cruciform…" — a foreground service that holds the process alive for
 * as long as the app-scoped [PairingCoordinator] has a session in flight. It does no
 * work itself (the pipeline runs on [HeyarrApp.appScope]); it exists so Android does
 * not kill the process while the user is over in Cruciform creating the key,
 * comparing the code and confirming — the relay wait can be minutes. Started and
 * stopped by [HeyarrApp] on the coordinator's state; the notification taps back into
 * the app. `dataSync` is the honest type: it is a network wait.
 */
class PairingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HeyarrApp
        val sameDevice = (app.pairing.state.value as? PairingState.Live)?.sameDevice ?: true
        startForeground(
            NOTIFICATION_ID,
            notification(if (sameDevice) "Pairing with Cruciform…" else "Pairing with your other device…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Device pairing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone is being enrolled as a heyarr device."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Waiting on the relay — switch back to heyarr to compare the security code.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pairing"
        const val NOTIFICATION_ID = 0x9A1
    }
}
