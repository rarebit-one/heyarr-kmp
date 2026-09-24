package one.rarebit.heyarr.mobile.consumption

import android.content.Context
import java.util.UUID

/**
 * What this phone is to a node's device registry: a stable `device_key` of our own
 * (the enrolled device key when there is one, else a UUID minted once), and the
 * `device_id` each node answered with — per node, since ids are the node's.
 */
interface DeviceIdStore {
    fun deviceKey(): String
    fun deviceId(baseUrl: String): String?
    fun putDeviceId(baseUrl: String, id: String)
}

class PrefsDeviceIdStore(context: Context) : DeviceIdStore {
    private val prefs = context.applicationContext.getSharedPreferences("heyarr-device", Context.MODE_PRIVATE)

    override fun deviceKey(): String {
        prefs.getString(KEY, null)?.let { return it }
        val fresh = "phone-" + UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }

    override fun deviceId(baseUrl: String): String? = prefs.getString(ID_PREFIX + baseUrl.trimEnd('/'), null)

    override fun putDeviceId(baseUrl: String, id: String) {
        prefs.edit().putString(ID_PREFIX + baseUrl.trimEnd('/'), id).apply()
    }

    private companion object {
        const val KEY = "device_key"
        const val ID_PREFIX = "device_id."
    }
}

class InMemoryDeviceIdStore(private val key: String = "phone-test") : DeviceIdStore {
    private val ids = HashMap<String, String>()
    override fun deviceKey() = key
    override fun deviceId(baseUrl: String) = ids[baseUrl.trimEnd('/')]
    override fun putDeviceId(baseUrl: String, id: String) {
        ids[baseUrl.trimEnd('/')] = id
    }
}
