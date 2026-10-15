package one.rarebit.heyarr.desktop.ssdp

import one.rarebit.heyarr.core.mcp.SsdpParser
import one.rarebit.heyarr.core.mcp.SsdpResponse
import one.rarebit.heyarr.core.mcp.SsdpScanner
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * JVM SSDP scanner: sends an M-SEARCH multicast for `MediaRenderer:1` and collects
 * responses for approximately [windowMs] milliseconds, deduplicating by USN.
 *
 * Uses a plain [DatagramSocket] (bound to any local port) rather than a
 * [java.net.MulticastSocket] because we only need to *send* to the multicast address
 * and *receive* unicast replies — the devices respond directly to our source port.
 *
 * Never throws: any I/O error is logged to stderr and an empty list is returned,
 * because a renderer scan failure is non-fatal (the server's own list is the fallback).
 */
class JvmSsdpScanner(private val windowMs: Int = 3000) : SsdpScanner {
    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RECEIVE_TIMEOUT_MS = 200
        private const val MAX_PACKET_BYTES = 2048
    }

    override fun scan(): List<SsdpResponse> = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_TIMEOUT_MS
            val request = SsdpParser.msearchRequest().toByteArray(Charsets.UTF_8)
            val addr = InetAddress.getByName(SSDP_ADDRESS)
            socket.send(DatagramPacket(request, request.size, addr, SSDP_PORT))
            collectResponses(socket, System.currentTimeMillis() + windowMs).values.toList()
        }
    } catch (e: IOException) {
        System.err.println("SSDP scan failed: ${e.message}")
        emptyList()
    }

    private fun collectResponses(socket: DatagramSocket, deadline: Long): Map<String, SsdpResponse> {
        val results = mutableMapOf<String, SsdpResponse>()
        val buf = ByteArray(MAX_PACKET_BYTES)
        while (System.currentTimeMillis() < deadline) {
            val recvPacket = DatagramPacket(buf, buf.size)
            try {
                socket.receive(recvPacket)
                val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                val response = SsdpParser.parseResponse(text) ?: continue
                results.putIfAbsent(response.usn, response)
            } catch (_: SocketTimeoutException) {
                // no packet in this 200 ms window — keep looping until deadline
            }
        }
        return results
    }
}
