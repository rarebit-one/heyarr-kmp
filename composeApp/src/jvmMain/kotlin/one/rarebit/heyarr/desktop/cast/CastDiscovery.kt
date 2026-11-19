package one.rarebit.heyarr.desktop.cast

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

internal data class CastDevice(val id: String, val name: String, val address: Inet4Address, val port: Int)

/**
 * Browse each usable IPv4 interface: multicast does not reliably cross interfaces. Each interface
 * gets its own JmDNS instance and its own browse window, so they run side by side (bounded) rather
 * than one after another: a laptop with Wi-Fi, Ethernet and a VPN or two used to wait a full
 * window per interface before the picker filled.
 */
internal class CastDiscovery(
    private val browse: (Inet4Address) -> List<ServiceInfo> = ::browseInterface,
    private val addresses: () -> List<Inet4Address> = ::usableAddresses,
) {
    fun discover(): List<CastDevice> {
        val targets = addresses()
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(targets.size.coerceAtMost(MAX_PARALLEL_BROWSES)) { task ->
            Thread(task, "heyarr-cast-discovery").apply { isDaemon = true }
        }
        return try {
            // invokeAll hands the futures back in submission order, so the concatenation (and with it
            // which duplicate select() keeps) is the same as the old sequential walk.
            val browses = targets.map { address ->
                Callable { runCatching { browse(address) }.getOrDefault(emptyList()) }
            }
            select(pool.invokeAll(browses).flatMap { it.get() })
        } finally {
            pool.shutdownNow()
        }
    }

    companion object {
        internal fun select(services: List<ServiceInfo>): List<CastDevice> = services.mapNotNull(::castDevice)
            .distinctBy(CastDevice::id).sortedBy(CastDevice::name)

        internal fun castDevice(info: ServiceInfo): CastDevice? {
            val address = info.inet4Addresses.firstOrNull() ?: return null
            return record(info.getPropertyString("id"), info.getPropertyString("fn"), info.name, address, info.port)
        }

        internal fun record(
            id: String?,
            friendlyName: String?,
            serviceName: String,
            address: Inet4Address,
            port: Int,
        ): CastDevice? {
            if (port !in 1..MAX_PORT) return null
            return CastDevice(
                id?.takeIf(String::isNotBlank) ?: "$serviceName@${address.hostAddress}",
                friendlyName?.takeIf(String::isNotBlank) ?: serviceName,
                address,
                port,
            )
        }

        private fun usableAddresses(): List<Inet4Address> = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>() }

        private fun browseInterface(address: Inet4Address): List<ServiceInfo> =
            JmDNS.create(address).use { it.list("_googlecast._tcp.local.", BROWSE_MS).toList() }

        private const val MAX_PORT = 65_535
        private const val BROWSE_MS = 1_500L

        // Each browse holds a multicast socket for one window; this caps sockets and threads on a
        // host with many virtual interfaces (container bridges, VPN tunnels) without serialising
        // the common two-or-three-interface case.
        private const val MAX_PARALLEL_BROWSES = 8
    }
}
