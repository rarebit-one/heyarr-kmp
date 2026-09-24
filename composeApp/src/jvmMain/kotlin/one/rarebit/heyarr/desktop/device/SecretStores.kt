package one.rarebit.heyarr.desktop.device

import java.io.File

/**
 * Picks the strongest available desktop secret store for the device keyring:
 *
 *   macOS Keychain  →  libsecret / Secret Service (Linux)  →  sealed file
 *
 * Selection is graceful: an unsupported OS, an unloadable native library, or a locked /
 * absent / headless keychain all fall back to [DesktopSecretStore] — the store never
 * disappears and enrolment never regresses to no-persistence.
 *
 * The availability probe (a keychain round-trip) is **deferred to first use** via
 * [LazySecretStore], so constructing a [DesktopDeviceKeyring] does no I/O — a preview or a
 * screenshot run that never reads the device key never touches the keychain.
 */
object SecretStores {

    /** The keyring's store: keychain where reachable, else the sealed file at [sealedDir]/[keyFile]. */
    fun forDevice(sealedDir: File, keyFile: File): SecretStore =
        LazySecretStore { select(DesktopSecretStore(sealedDir, keyFile)) }

    /** OS pick + availability probe, with [fallback] both as the migration source and the degrade target. */
    private fun select(fallback: DesktopSecretStore): SecretStore = selectWith(defaultBackend(), fallback)

    /** The selection rule, isolated from OS/native detection so it is unit-testable with a fake backend. */
    internal fun selectWith(backend: KeychainBackend?, fallback: DesktopSecretStore): SecretStore {
        if (backend != null && runCatching { backend.isAvailable() }.getOrDefault(false)) {
            return KeychainSecretStore(backend, legacy = fallback)
        }
        return fallback
    }

    /** The keychain backend for THIS OS, or null on an unsupported OS / native-load failure. */
    private fun defaultBackend(): KeychainBackend? {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        return runCatching {
            when {
                os.contains("mac") || os.contains("darwin") -> MacKeychainBackend()
                os.contains("linux") -> LibSecretBackend()
                else -> null
            }
        }.getOrNull()
    }
}

/** A [SecretStore] whose concrete backend is resolved (and its availability probed) on first use. */
internal class LazySecretStore(provider: () -> SecretStore) : SecretStore {
    private val delegate: SecretStore by lazy(provider)
    override val tier: KeyTier get() = delegate.tier
    override fun exists(name: String): Boolean = delegate.exists(name)
    override fun seal(name: String, secret: ByteArray) = delegate.seal(name, secret)
    override fun unseal(name: String): ByteArray? = delegate.unseal(name)
    override fun delete(name: String) = delegate.delete(name)
}
