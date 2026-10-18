package one.rarebit.heyarr.mobile.login

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import one.rarebit.heyarr.mobile.MainActivity

/**
 * "Signing in with Cruciform…": a foreground service that holds the process alive while
 * a QR login is handed off to Cruciform on this phone ("Approve on this phone"), so
 * Android does not freeze or kill heyarr, and with it the `GET /login/{id}` poll, while
 * the user approves over there (heyarr-kmp#172). It does no work itself: the poll runs
 * in [LoginCoordinator] on the app scope. Started and stopped by the app on the
 * coordinator's state, like the pairing service; `dataSync` is the honest type, a
 * network wait.
 */
class LoginForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sign-in", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while heyarr waits for a sign-in you are approving in Cruciform."
            },
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Signing in with Cruciform…")
            .setContentText("Waiting for your approval in Cruciform.")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "login"
        const val NOTIFICATION_ID = 0x9A2
    }
}
