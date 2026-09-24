package one.rarebit.heyarr.desktop.login

import one.rarebit.heyarr.core.auth.Credential

/**
 * The login seam — now framed as the **"Sign in to save" enrol upgrade** over the
 * guest default (heyarr-kmp Phase 3, ADR-0094 client section).
 *
 * The desktop's default is [Credential.Guest]: on a trusted network the client connects
 * with NO credential and browses + plays at once. A [LoginProvider] is the OPTIONAL
 * upgrade a person takes when they want to save wants, follows and their place — it yields
 * the credentialled shape to present instead of guest.
 *
 * Two upgrades exist:
 *   - [BearerTokenLogin] — the live one: a pasted `heyarr_<id>_<secret>` (ADR-0011) or a
 *     weblogin session token, presented directly with no round trip.
 *   - [DeviceLogin] — the primary, offline device credential (`Credential.Device`, the
 *     Gate B "cruciform" work). It yields a real device credential once the platform has an
 *     enrolment cert + a fresh possession proof; obtaining those is platform-gated (the key
 *     is non-exportable in the OS key store) and is produced by a [DeviceEnroller]. The
 *     concrete enroller — voidbind-client's device/QR coordinator — is not wired on desktop
 *     yet (it needs `voidbind-client` on :composeApp's classpath + a desktop key store), so
 *     the shipped enroller is [DeviceEnroller.NotEnrolled]. The SEAM is real: the moment an
 *     enroller is provided, the app presents `Device` in preference to bearer and guest.
 */
interface LoginProvider {
    /** A human label for the "Sign in to save" UI. */
    val displayName: String

    /** The credential to present to heyarr, or null when this upgrade is not configured. */
    fun credential(): Credential?
}

/**
 * The live upgrade: the bearer token the Settings screen pastes. Empty → null, so an
 * un-configured bearer upgrade simply leaves the client as a guest.
 */
class BearerTokenLogin(private val tokenProvider: () -> String) : LoginProvider {
    override val displayName = "Bearer token"

    override fun credential(): Credential? =
        tokenProvider().trim().takeIf { it.isNotEmpty() }?.let { Credential.Bearer(it) }
}

/**
 * Obtains an enrolled device's cert + fresh possession proof. The proof is signed
 * in-enclave by a non-exportable key, so this is platform-gated; the real implementation
 * is voidbind-client's device/QR coordinator over a desktop `DeviceKeyStore`.
 */
fun interface DeviceEnroller {
    /** The enrolled cert and a fresh possession proof, or null when this machine is not (yet) enrolled. */
    fun enrolled(): Credential.Device?

    companion object {
        /** The shipped default until the voidbind device coordinator is wired on desktop. */
        val NotEnrolled = DeviceEnroller { null }
    }
}

/**
 * The device-credential upgrade: presents `Device <cert>~<proof>` once [enroller] reports
 * an enrolment. Inert (falls through to bearer/guest) while the enroller is
 * [DeviceEnroller.NotEnrolled].
 */
class DeviceLogin(private val enroller: DeviceEnroller = DeviceEnroller.NotEnrolled) : LoginProvider {
    override val displayName = "This device (Voidbind)"

    override fun credential(): Credential? = enroller.enrolled()
}

/**
 * Resolves the credential to present, newest-first over the enrol upgrades, else guest.
 * A device enrolment wins over a pasted bearer token (it is the primary, offline
 * credential); with neither, the client stays a [Credential.Guest].
 */
class EnrolUpgrade(
    private val device: LoginProvider = DeviceLogin(),
    private val bearer: LoginProvider,
) {
    /** The credential to present; never null — falls back to [Credential.Guest]. */
    fun credential(): Credential = device.credential() ?: bearer.credential() ?: Credential.Guest

    /** True when a real enrolment (device or bearer) is configured — i.e. not a guest. */
    fun isEnrolled(): Boolean = credential() !is Credential.Guest
}
