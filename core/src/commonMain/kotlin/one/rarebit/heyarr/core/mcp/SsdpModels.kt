package one.rarebit.heyarr.core.mcp

/**
 * Parsed SSDP M-SEARCH response headers for a single device advertisement.
 *
 * [location] is the UPnP device description URL reported by the device.
 * [st]       is the search target (service type) the device answered for.
 * [usn]      is the unique service name used for deduplication.
 */
data class SsdpResponse(val location: String, val st: String, val usn: String)

/** Pure SSDP header parsing (no I/O). Shared by `:core` tests and the JVM socket scanner. */
object SsdpParser {
    private const val MEDIARENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"
    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900

    /**
     * Build the M-SEARCH multicast packet for `urn:schemas-upnp-org:device:MediaRenderer:1`.
     * Uses `MX: 3` so devices respond within 3 seconds.
     */
    fun msearchRequest(mx: Int = 3): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: $MEDIARENDERER_ST\r\n")
        append("\r\n")
    }

    /**
     * Parse a raw M-SEARCH response text into an [SsdpResponse].
     * Returns null when the response does not carry all three required headers,
     * or when the status line is not a 200 OK.
     */
    fun parseResponse(text: String): SsdpResponse? {
        val lines = text.lines()
        // First line must be a 200 OK (SSDP responses, not NOTIFY messages)
        val status = lines.firstOrNull() ?: return null
        if (!status.contains("200")) return null

        var location: String? = null
        var st: String? = null
        var usn: String? = null

        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val key = line.substring(0, colon).trim().uppercase()
            val value = line.substring(colon + 1).trim()
            when (key) {
                "LOCATION" -> location = value
                "ST" -> st = value
                "USN" -> usn = value
            }
        }

        if (location.isNullOrBlank() || st.isNullOrBlank() || usn.isNullOrBlank()) return null
        return SsdpResponse(location = location, st = st, usn = usn)
    }
}

/**
 * Abstraction over the UDP multicast socket so tests can fake the network.
 *
 * A real JVM implementation lives in `:composeApp` jvmMain as [one.rarebit.heyarr.desktop.ssdp.JvmSsdpScanner].
 */
interface SsdpScanner {
    /**
     * Send an M-SEARCH for `MediaRenderer:1` and collect responses for ~3 seconds.
     * Deduplicates by USN. Never throws: network errors are logged and an empty list returned.
     */
    fun scan(): List<SsdpResponse>
}
