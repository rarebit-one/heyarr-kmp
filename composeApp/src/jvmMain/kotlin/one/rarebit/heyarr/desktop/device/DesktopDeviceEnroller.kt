package one.rarebit.heyarr.desktop.device

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.desktop.login.DeviceEnroller

/**
 * The real desktop [DeviceEnroller] — replaces [DeviceEnroller.NotEnrolled]. It reports
 * a live [Credential.Device] built from the [keyring]'s persisted admitting op plus a
 * FRESH possession proof (software-signed over the sealed seed, no prompt on desktop),
 * or null when this desktop has not been paired in — in which case the enrol upgrade
 * falls through to a pasted bearer token, else guest.
 *
 * `enrolled()` is read on the composition thread each time the client rebuilds its API
 * (`AppSession.credential`); the underlying [one.rarebit.voidwhichbinds.auth.DeviceCredential]
 * reuses one short proof across its reuse window, so this signs only every couple of
 * minutes, not per call.
 *
 * A device key the keychain will not unlock (locked session, a broken Secret Service) reads
 * as "not enrolled" here rather than throwing: this runs during composition, and the keyring
 * refuses to mint a replacement key over an enrolled one, so the honest fallback is the
 * bearer token / guest until the keychain answers again.
 */
class DesktopDeviceEnroller(private val keyring: DesktopDeviceKeyring) : DeviceEnroller {

    override fun enrolled(): Credential.Device? {
        val credential = runCatching { keyring.deviceCredential() }.getOrNull() ?: return null
        val presentation = runCatching { credential.current() }.getOrNull() ?: return null
        return Credential.Device(presentation.cert, presentation.proof)
    }
}
