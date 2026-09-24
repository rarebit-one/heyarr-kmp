package one.rarebit.heyarr.desktop.device

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/**
 * macOS login-Keychain backend via JNA to the system **Security** + **CoreFoundation**
 * frameworks — `SecItemAdd` / `SecItemCopyMatching` / `SecItemUpdate` / `SecItemDelete` over
 * `kSecClassGenericPassword` items, keyed by (service, account).
 *
 * ## Why JNA-direct rather than a third-party keychain lib
 *
 * JNA is already a project dependency (the mpv `--wid` handle), so this adds **no new
 * dependency**. The native surface is tiny and fully auditable, there is **no bespoke
 * crypto** (the OS owns storage, encryption and the access-control list), and going direct
 * lets this class honour [KeychainBackend]'s "never throw" contract exactly — a locked or
 * unreachable keychain returns false/null instead of surfacing a library's exception type.
 *
 * Items are added with `kSecAttrAccessibleWhenUnlocked`, i.e. readable while the user's login
 * keychain is unlocked — the device seed must be usable each launch without a prompt.
 *
 * CoreFoundation refs created here (`CFString`, `CFData`, the query `CFDictionary`) are
 * released in `finally`; a returned `CFData` from a copy is released after its bytes are read.
 */
internal class MacKeychainBackend(
    private val service: String = KeychainSecretStore.SERVICE,
) : KeychainBackend {

    override val label = "macOS Keychain"

    override fun isAvailable(): Boolean = runCatching {
        // A benign lookup. errSecItemNotFound (or success) means the keychain answered and is
        // usable; an interaction/locked error means treat as unavailable and fall back.
        when (lookup("~heyarr.probe~").status) {
            ERR_SUCCESS, ERR_ITEM_NOT_FOUND -> true
            else -> false
        }
    }.getOrDefault(false)

    override fun store(account: String, secret: ByteArray): Boolean = runCatching {
        val add = CFDict()
        try {
            add.put(kSecClass, kSecClassGenericPassword)
            add.putString(kSecAttrService, service)
            add.putString(kSecAttrAccount, account)
            add.putData(kSecValueData, secret)
            add.put(kSecAttrAccessible, kSecAttrAccessibleWhenUnlocked)
            when (SEC.SecItemAdd(add.ref, null)) {
                ERR_SUCCESS -> true
                ERR_DUPLICATE_ITEM -> update(account, secret)
                else -> false
            }
        } finally {
            add.release()
        }
    }.getOrDefault(false)

    private fun update(account: String, secret: ByteArray): Boolean {
        val query = CFDict()
        val attrs = CFDict()
        try {
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, account)
            attrs.putData(kSecValueData, secret)
            return SEC.SecItemUpdate(query.ref, attrs.ref) == ERR_SUCCESS
        } finally {
            query.release()
            attrs.release()
        }
    }

    override fun retrieve(account: String): ByteArray? = runCatching { lookup(account).data }.getOrNull()

    override fun remove(account: String) {
        runCatching {
            val query = CFDict()
            try {
                query.put(kSecClass, kSecClassGenericPassword)
                query.putString(kSecAttrService, service)
                query.putString(kSecAttrAccount, account)
                SEC.SecItemDelete(query.ref)
            } finally {
                query.release()
            }
        }
    }

    private class Result(val status: Int, val data: ByteArray?)

    private fun lookup(account: String): Result {
        val query = CFDict()
        try {
            query.put(kSecClass, kSecClassGenericPassword)
            query.putString(kSecAttrService, service)
            query.putString(kSecAttrAccount, account)
            query.put(kSecReturnData, kCFBooleanTrue)
            query.put(kSecMatchLimit, kSecMatchLimitOne)
            val out = PointerByReference()
            val status = SEC.SecItemCopyMatching(query.ref, out)
            if (status != ERR_SUCCESS) return Result(status, null)
            val cfData = out.value ?: return Result(status, null)
            try {
                val len = CF.CFDataGetLength(cfData).toInt()
                val ptr = CF.CFDataGetBytePtr(cfData)
                val bytes = if (len > 0 && ptr != null) ptr.getByteArray(0L, len) else ByteArray(0)
                return Result(status, bytes)
            } finally {
                CF.CFRelease(cfData)
            }
        } finally {
            query.release()
        }
    }

    /** A mutable CoreFoundation dictionary that tracks and releases the CF values it created. */
    private class CFDict {
        val ref: Pointer = CF.CFDictionaryCreateMutable(
            null,
            NativeLong(0),
            CF_DICT_KEY_CALLBACKS,
            CF_DICT_VALUE_CALLBACKS,
        ) ?: error("CFDictionaryCreateMutable returned null")

        private val owned = ArrayList<Pointer>()

        fun put(key: Pointer, value: Pointer) = CF.CFDictionaryAddValue(ref, key, value)

        fun putString(key: Pointer, value: String) {
            val cf = cfString(value)
            owned.add(cf)
            CF.CFDictionaryAddValue(ref, key, cf)
        }

        fun putData(key: Pointer, value: ByteArray) {
            val cf = cfData(value)
            owned.add(cf)
            CF.CFDictionaryAddValue(ref, key, cf)
        }

        fun release() {
            owned.forEach { CF.CFRelease(it) }
            CF.CFRelease(ref)
        }
    }
}

// ── native bindings (file-private; loaded lazily so referencing this file off-macOS is safe) ──

private interface CoreFoundation : Library {
    fun CFStringCreateWithCString(alloc: Pointer?, cStr: ByteArray, encoding: Int): Pointer?
    fun CFDataCreate(alloc: Pointer?, bytes: ByteArray, length: NativeLong): Pointer?
    fun CFDictionaryCreateMutable(alloc: Pointer?, capacity: NativeLong, keyCallBacks: Pointer?, valueCallBacks: Pointer?): Pointer?
    fun CFDictionaryAddValue(theDict: Pointer, key: Pointer, value: Pointer)
    fun CFRelease(cf: Pointer)
    fun CFDataGetLength(theData: Pointer): NativeLong
    fun CFDataGetBytePtr(theData: Pointer): Pointer?
}

private interface Security : Library {
    fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemUpdate(query: Pointer, attributesToUpdate: Pointer): Int
    fun SecItemDelete(query: Pointer): Int
}

private const val ERR_SUCCESS = 0
private const val ERR_ITEM_NOT_FOUND = -25300
private const val ERR_DUPLICATE_ITEM = -25299
private const val K_CF_STRING_ENCODING_UTF8 = 0x08000100

private val CF: CoreFoundation by lazy { Native.load("CoreFoundation", CoreFoundation::class.java) }
private val SEC: Security by lazy { Native.load("Security", Security::class.java) }
private val CF_LIB: NativeLibrary by lazy { NativeLibrary.getInstance("CoreFoundation") }
private val SEC_LIB: NativeLibrary by lazy { NativeLibrary.getInstance("Security") }

/** `const CFStringRef X` — dereference one level: the symbol holds the CFString pointer value. */
private fun secConst(name: String): Pointer = SEC_LIB.getGlobalVariableAddress(name).getPointer(0)
private fun cfConst(name: String): Pointer = CF_LIB.getGlobalVariableAddress(name).getPointer(0)

/** `const CFDictionaryKeyCallBacks X` — pass the ADDRESS of the struct itself (no deref). */
private val CF_DICT_KEY_CALLBACKS: Pointer by lazy { CF_LIB.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks") }
private val CF_DICT_VALUE_CALLBACKS: Pointer by lazy { CF_LIB.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks") }

private val kSecClass by lazy { secConst("kSecClass") }
private val kSecClassGenericPassword by lazy { secConst("kSecClassGenericPassword") }
private val kSecAttrService by lazy { secConst("kSecAttrService") }
private val kSecAttrAccount by lazy { secConst("kSecAttrAccount") }
private val kSecValueData by lazy { secConst("kSecValueData") }
private val kSecReturnData by lazy { secConst("kSecReturnData") }
private val kSecMatchLimit by lazy { secConst("kSecMatchLimit") }
private val kSecMatchLimitOne by lazy { secConst("kSecMatchLimitOne") }
private val kSecAttrAccessible by lazy { secConst("kSecAttrAccessible") }
private val kSecAttrAccessibleWhenUnlocked by lazy { secConst("kSecAttrAccessibleWhenUnlocked") }
private val kCFBooleanTrue by lazy { cfConst("kCFBooleanTrue") }

private fun cfString(s: String): Pointer {
    return CF.CFStringCreateWithCString(null, nulTerminated(s), K_CF_STRING_ENCODING_UTF8)
        ?: error("CFStringCreateWithCString returned null")
}

/** UTF-8 bytes of [s] with a trailing NUL — CFStringCreateWithCString wants a C string. */
private fun nulTerminated(s: String): ByteArray {
    val utf8 = s.toByteArray(Charsets.UTF_8)
    return utf8.copyOf(utf8.size + 1)
}

private fun cfData(bytes: ByteArray): Pointer =
    CF.CFDataCreate(null, bytes, NativeLong(bytes.size.toLong())) ?: error("CFDataCreate returned null")
