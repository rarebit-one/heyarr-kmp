package one.rarebit.heyarr.desktop.cast

import java.net.Inet4Address
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

internal data class CastDevice(val id: String, val name: String, val address: Inet4Address, val port: Int)

/** Browse each usable IPv4 interface: multicast does not reliably cross interfaces. */
internal class CastDiscovery(private val browse: (Inet4Address) -> List<ServiceInfo> = ::browseInterface) {
    fun discover(): List<CastDevice> = NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
        .flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>() }
        .flatMap { address -> runCatching { browse(address) }.getOrDefault(emptyList()) }
        .let(::select)

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

        private fun browseInterface(address: Inet4Address): List<ServiceInfo> =
            JmDNS.create(address).use { it.list("_googlecast._tcp.local.", BROWSE_MS).toList() }

        private const val MAX_PORT = 65_535
        private const val BROWSE_MS = 1_500L
    }
}
