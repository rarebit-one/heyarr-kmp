package one.rarebit.heyarr.mobile.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import one.rarebit.heyarr.core.discovery.Discovery
import one.rarebit.heyarr.core.discovery.MdnsHit
import one.rarebit.heyarr.core.discovery.MdnsResolver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Android [MdnsResolver] actual: browse the LAN for the first `_heyarr._tcp`
 * advertiser with [NsdManager] (`android.net.nsd`) and hand its SRV host+port + TXT
 * `tls` flag to the pure fallback chain in `:core`
 * ([one.rarebit.heyarr.core.discovery.NodeDiscovery]). The desktop half is
 * `JmdnsResolver`; both keep every platform-specific detail — the DNS-SD socket, the
 * timeout, the TXT parsing — out of `:core`.
 *
 * Fully defensive: any failure (the service never resolves, NSD reports an error, the
 * OS has multicast filtered) is swallowed and reported as "no hit", so discovery
 * degrades to the split-horizon DNS name rather than throwing. **Blocking** — call off
 * the main thread (the ViewModel runs it on `Dispatchers.IO`). The TXT parsing itself is
 * a pure function ([NsdMdns.hitFrom]) so it is unit-tested with no NSD service.
 */
class NsdMdnsResolver(
    context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : MdnsResolver {

    private val appContext = context.applicationContext

    override fun resolve(): MdnsHit? = runCatching { browseAndResolve() }.getOrNull()

    private fun browseAndResolve(): MdnsHit? {
        val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val hit = AtomicReference<MdnsHit?>(null)
        val done = CountDownLatch(1)
        // NSD wants the DNS-SD service type with a trailing dot: `_heyarr._tcp.`.
        val type = Discovery.SERVICE_TYPE + "."

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                done.countDown()
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                hit.set(serviceInfo?.let { NsdMdns.hitOf(it) })
                done.countDown()
            }
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            @Volatile var resolving = false
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                done.countDown()
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                // Resolve the FIRST advertiser only; a node's own type match is enough.
                if (resolving || serviceInfo == null) return
                resolving = true
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                    .onFailure { done.countDown() }
            }
        }

        return try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            hit.get()
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}

/**
 * The pure half of the Android resolver: turn a resolved [NsdServiceInfo]'s host, port
 * and TXT attributes into a [MdnsHit], or null when the SRV data is unusable. Kept
 * apart from the NSD callbacks so the `tls` flag parse and the port/host guards are
 * unit-tested without a live service.
 */
object NsdMdns {

    /** Read a TXT attribute as UTF-8, or null when absent/empty. */
    fun txt(attributes: Map<String, ByteArray?>?, key: String): String? =
        attributes?.get(key)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

    /** Pure: build the hit from the resolved parts. `tls=1` (TXT) means https; a missing host or non-positive port is no hit. */
    fun hitFrom(host: String?, port: Int, tls: String?, path: String?): MdnsHit? {
        if (host.isNullOrBlank() || port <= 0) return null
        return MdnsHit(host = host, port = port, tls = tls?.trim() == "1", path = path)
    }

    /** Adapt a resolved [NsdServiceInfo] — its host literal, port and TXT `tls`/`path`. */
    fun hitOf(info: NsdServiceInfo): MdnsHit? {
        val attrs: Map<String, ByteArray?> = runCatching { info.attributes }.getOrNull() ?: emptyMap()
        val host = runCatching {
            @Suppress("DEPRECATION")
            info.host?.hostAddress
        }.getOrNull()
        return hitFrom(host = host, port = info.port, tls = txt(attrs, "tls"), path = txt(attrs, "path"))
    }
}
