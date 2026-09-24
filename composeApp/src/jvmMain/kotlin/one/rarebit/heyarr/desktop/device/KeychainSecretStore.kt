package one.rarebit.heyarr.desktop.device

/**
 * A [SecretStore] that hands each named secret to the OS keychain via [backend], reported as
 * the [KeyTier.KEYCHAIN] tier. The secret bytes never touch the disk (no sealed file, no
 * local wrap key) — the OS owns storage, encryption and access control. This is the honest
 * step up from [DesktopSecretStore]'s at-rest sealing: an attacker with a copy of the disk
 * has nothing, and code running as this OS user must go through the keychain's own access
 * control to read the seed rather than just reading a 0600 file.
 *
 * ## Migration (never lose an enrolment)
 *
 * A [legacy] sealed-file store may be passed. If a secret is absent from the keychain but
 * present there (an install that enrolled under #35, before this feature), [unseal] reads it
 * from the sealed file, re-stores it in the keychain and deletes the sealed copy — a one-time
 * upgrade that neither regenerates the device key nor leaves the seed on disk afterwards.
 */
class KeychainSecretStore(
    private val backend: KeychainBackend,
    private val legacy: DesktopSecretStore? = null,
    private val service: String = SERVICE,
) : SecretStore {

    override val tier: KeyTier get() = KeyTier.KEYCHAIN

    override fun exists(name: String): Boolean = backend.retrieve(key(name)) != null || legacy?.exists(name) == true

    override fun seal(name: String, secret: ByteArray) {
        check(backend.store(key(name), secret)) { "keychain rejected the write for '$name'" }
        // Drop any pre-migration sealed copy so the seed is not left on disk.
        legacy?.delete(name)
    }

    override fun unseal(name: String): ByteArray? {
        backend.retrieve(key(name))?.let { return it }
        // One-time migration of a secret sealed before this feature existed.
        val migrated = legacy?.unseal(name) ?: return null
        if (backend.store(key(name), migrated)) legacy.delete(name)
        return migrated
    }

    override fun delete(name: String) {
        backend.remove(key(name))
        legacy?.delete(name)
    }

    /** The keychain account for a keyring secret name — namespaced under the desktop service. */
    private fun key(name: String) = "$service.$name"

    companion object {
        /** The generic-password service every heyarr-desktop keychain item lives under. */
        const val SERVICE = "one.rarebit.heyarr.desktop"
    }
}
