package one.rarebit.heyarr.mobile.login

import android.content.Context
import android.content.SharedPreferences

/**
 * The open QR login, in app-private SharedPreferences, so a process death while the user
 * is in Cruciform does not lose it ([LoginCoordinator] resumes the poll on restart).
 * The login id is a short-lived bearer handle for the node's `GET /login/{id}` (whoever
 * polls it after approval collects the session token), so it is kept only while the
 * login is open: cleared the moment it is approved, declined, expires or is cancelled.
 */
class PrefsPendingLoginStore(context: Context) : PendingLoginStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("heyarr-login", Context.MODE_PRIVATE)

    override var pending: PendingLogin?
        get() {
            val rp = prefs.getString(KEY_RP, null) ?: return null
            val id = prefs.getString(KEY_ID, null) ?: return null
            val qr = prefs.getString(KEY_QR, null) ?: return null
            return PendingLogin(
                rpBase = rp,
                loginId = id,
                qrTuple = qr,
                startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
                handedOff = prefs.getBoolean(KEY_HANDED_OFF, false),
            )
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    clear()
                } else {
                    putString(KEY_RP, value.rpBase)
                    putString(KEY_ID, value.loginId)
                    putString(KEY_QR, value.qrTuple)
                    putLong(KEY_STARTED_AT, value.startedAtMillis)
                    putBoolean(KEY_HANDED_OFF, value.handedOff)
                }
            }.apply()
        }

    private companion object {
        const val KEY_RP = "rp"
        const val KEY_ID = "id"
        const val KEY_QR = "qr"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_HANDED_OFF = "handed_off"
    }
}
