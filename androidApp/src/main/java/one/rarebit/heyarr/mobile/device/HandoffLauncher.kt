package one.rarebit.heyarr.mobile.device

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Fires a `voidbind:` URI at whatever app handles the scheme (Cruciform, the Voidbind
 * authenticator app, `one.rarebit.cruciform`, on the same phone). [canOpen] is what the
 * UI uses to show or hide the "Open in Cruciform" / "Approve on this phone" buttons —
 * the QR stays as the fallback when nothing resolves. Needs the `<queries>` entry in
 * the manifest for package visibility on Android 11+.
 */
object HandoffLauncher {

    private fun intent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    fun canOpen(context: Context, uri: String): Boolean = intent(uri).resolveActivity(context.packageManager) != null

    /** Open the URI; returns false if no activity took it. */
    fun open(context: Context, uri: String): Boolean = try {
        context.startActivity(intent(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
