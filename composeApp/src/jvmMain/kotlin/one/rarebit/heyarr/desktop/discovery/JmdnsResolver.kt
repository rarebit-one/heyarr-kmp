package one.rarebit.heyarr.desktop.discovery

import one.rarebit.heyarr.core.discovery.Discovery
import one.rarebit.heyarr.core.discovery.MdnsHit
import one.rarebit.heyarr.core.discovery.MdnsResolver
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/**
 * The desktop [MdnsResolver] actual: browse the LAN for the first `_heyarr._tcp`
 * advertiser and hand its SRV host+port + TXT `tls` flag to the pure fallback chain in
 * `:core`. Everything platform-specific — the jmdns socket, the timeout, the DNS-SD
 * parsing — is confined here; `:core` stays free of any JVM-only mDNS.
 *
 * Fully defensive: any failure (jmdns cannot open a multicast socket, the network has no
 * responder, a malformed record) is swallowed and reported as "no hit", so discovery
 * degrades to the DNS name rather than throwing. Blocking — call off the UI thread.
 */
class JmdnsResolver(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) : MdnsResolver {

    override fun resolve(): MdnsHit? = runCatching {
        JmDNS.create(InetAddress.getLocalHost()).use { jmdns ->
            // "<type>.local." is the DNS-SD form jmdns expects.
            val type = "${Discovery.SERVICE_TYPE}.local."
            val services: Array<ServiceInfo> = jmdns.list(type, timeoutMs)
            services.asSequence().mapNotNull { it.toHit() }.firstOrNull()
        }
    }.getOrNull()

    private fun ServiceInfo.toHit(): MdnsHit? {
        // Prefer the resolved host name; fall back to the first address literal.
        val host = hostAddresses.firstOrNull()
            ?: inetAddresses.firstOrNull()?.hostAddress
            ?: return null
        if (port <= 0) return null
        val tls = getPropertyString("tls")?.trim() == "1"
        val path = getPropertyString("path")
        return MdnsHit(host = host, port = port, tls = tls, path = path)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 1_500L
    }
}
