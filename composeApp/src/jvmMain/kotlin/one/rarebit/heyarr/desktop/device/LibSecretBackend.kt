package one.rarebit.heyarr.desktop.device

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.util.Base64

/**
 * Linux freedesktop **Secret Service** backend via JNA to **libsecret**'s simple password
 * API — `secret_password_store_sync` / `secret_password_lookup_sync` /
 * `secret_password_clear_sync`, keyed by a heyarr-desktop schema whose one `account`
 * attribute names the secret. libsecret talks to the running Secret Service (gnome-keyring,
 * or KWallet's org.freedesktop.Secret provider) over the session D-Bus; where none runs —
 * a headless box, no session bus — every call sets a `GError` and is treated as unavailable,
 * so [SecretStores] falls back to the sealed file.
 *
 * libsecret's simple API stores TEXT passwords, so the raw seed bytes are Base64-wrapped on
 * the way in and decoded on the way out. No bespoke crypto: the Secret Service owns storage,
 * encryption and access control.
 *
 * ## Why JNA-direct
 *
 * Same rationale as [MacKeychainBackend]: JNA is already a dependency, the surface is tiny,
 * and going direct lets every call honour [KeychainBackend]'s never-throw contract (a `GError`
 * or an unloadable library degrades to false/null rather than surfacing).
 *
 * ## NOT verified on the build host
 *
 * The macOS build host cannot exercise this native path; it is covered in tests only through
 * the [KeychainBackend] fake (the store abstraction + platform selection). The real libsecret
 * calls are deliberately isolated here behind the interface, and every one is wrapped so a
 * layout / soname / D-Bus problem falls back to the sealed file rather than failing the app.
 */
internal class LibSecretBackend(private val service: String = KeychainSecretStore.SERVICE) : KeychainBackend {

    override val label = "libsecret (Secret Service)"

    override fun isAvailable(): Boolean = runCatching {
        // A benign lookup. If no Secret Service is reachable libsecret sets a GError; treat
        // any GError (or a native failure) as "fall back to the sealed file".
        val err = PointerByReference()
        val res = LIBSECRET.secret_password_lookup_sync(SCHEMA, null, err, ATTR_ACCOUNT, "~heyarr.probe~", null)
        if (res != null) LIBSECRET.secret_password_free(res)
        clearError(err)
    }.getOrDefault(false)

    override fun store(account: String, secret: ByteArray): Boolean = runCatching {
        val err = PointerByReference()
        val encoded = Base64.getEncoder().encodeToString(secret)
        val ok = LIBSECRET.secret_password_store_sync(
            SCHEMA, COLLECTION_DEFAULT, "$service/$account", encoded, null, err,
            ATTR_ACCOUNT, account, null,
        )
        clearError(err) && ok
    }.getOrDefault(false)

    override fun retrieve(account: String): ByteArray? = runCatching {
        val err = PointerByReference()
        val res = LIBSECRET.secret_password_lookup_sync(SCHEMA, null, err, ATTR_ACCOUNT, account, null)
        if (!clearError(err) || res == null) return@runCatching null
        val text = res.getString(0L)
        LIBSECRET.secret_password_free(res)
        runCatching { Base64.getDecoder().decode(text) }.getOrNull()
    }.getOrNull()

    override fun remove(account: String) {
        runCatching {
            val err = PointerByReference()
            LIBSECRET.secret_password_clear_sync(SCHEMA, null, err, ATTR_ACCOUNT, account, null)
            clearError(err)
        }
    }
}

// ── native bindings (file-private; loaded lazily so referencing this file off-Linux is safe) ──

private const val ATTR_ACCOUNT = "account"
private const val COLLECTION_DEFAULT = "default" // SECRET_COLLECTION_DEFAULT
private const val SECRET_SCHEMA_NONE = 0
private const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0

private interface LibSecret : Library {
    // The trailing attribute name/value pairs are passed as varargs, NULL-terminated.
    fun secret_password_store_sync(
        schema: SecretSchema,
        collection: String?,
        label: String,
        password: String,
        cancellable: Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean

    fun secret_password_lookup_sync(
        schema: SecretSchema,
        cancellable: Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Pointer?

    fun secret_password_clear_sync(
        schema: SecretSchema,
        cancellable: Pointer?,
        error: PointerByReference?,
        vararg attributes: Any?,
    ): Boolean

    fun secret_password_free(password: Pointer)
}

private interface GLib : Library {
    fun g_error_free(error: Pointer)
}

private val LIBSECRET: LibSecret by lazy { Native.load("secret-1", LibSecret::class.java) }
private val GLIB: GLib by lazy { Native.load("glib-2.0", GLib::class.java) }

/** The static heyarr-desktop schema: one string attribute, "account". Mirrors libsecret's
 *  `SecretSchema` layout (name, flags, attributes[32], then private reserved fields). */
private val SCHEMA: SecretSchema by lazy {
    SecretSchema().apply {
        name = "one.rarebit.heyarr.desktop.DeviceSecret"
        flags = SECRET_SCHEMA_NONE
        attributes[0].name = ATTR_ACCOUNT
        attributes[0].type = SECRET_SCHEMA_ATTRIBUTE_STRING
        // attributes[1..] left zeroed → the NULL-named terminator libsecret expects.
        write()
    }
}

/** Frees any GError set at [err] and reports whether the call was clean (no error). */
private fun clearError(err: PointerByReference): Boolean {
    val e = err.value ?: return true
    runCatching { GLIB.g_error_free(e) }
    return false
}

@Structure.FieldOrder("name", "type")
internal open class SecretSchemaAttribute : Structure() {
    @JvmField var name: String? = null

    @JvmField var type: Int = 0
}

@Structure.FieldOrder(
    "name", "flags", "attributes",
    "reserved", "reserved1", "reserved2", "reserved3", "reserved4", "reserved5", "reserved6", "reserved7",
)
internal open class SecretSchema : Structure() {
    @JvmField var name: String? = null

    @JvmField var flags: Int = 0

    @JvmField
    var attributes: Array<SecretSchemaAttribute> =
        @Suppress("UNCHECKED_CAST")
        (SecretSchemaAttribute().toArray(32) as Array<SecretSchemaAttribute>)

    // Private reserved fields (gint + 7 gpointer) — declared so the struct size/layout match.
    @JvmField var reserved: Int = 0

    @JvmField var reserved1: Pointer? = null

    @JvmField var reserved2: Pointer? = null

    @JvmField var reserved3: Pointer? = null

    @JvmField var reserved4: Pointer? = null

    @JvmField var reserved5: Pointer? = null

    @JvmField var reserved6: Pointer? = null

    @JvmField var reserved7: Pointer? = null
}
