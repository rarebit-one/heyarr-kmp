package one.rarebit.heyarr.desktop.cast

import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Manual protocol smoke test. Discovery and status do not launch media. The other
 * commands deliberately require a receiver ID and are never run by automated tests.
 */
fun main(args: Array<String>) {
    val devices = CastDiscovery().discover()
    if (args.firstOrNull() == "list") {
        devices.forEach { println("${it.id}\t${it.name}") }
        return
    }
    val command = args.firstOrNull() ?: error("Usage: castProbe list|status|load-paused|pause|resume|seek|stop")
    val id = args.getOrNull(1) ?: error("A discovered receiver ID is required")
    val device = devices.firstOrNull { it.id == id } ?: error("Receiver was not discovered")
    CastConnection(device).use { channel -> runCommand(channel, device, command, args) }
}

private fun runCommand(channel: CastConnection, device: CastDevice, command: String, args: Array<String>) {
    with(channel) {
        when (command) {
            "status" -> println(channel.refresh() ?: "No Default Media Receiver session")

            "load-paused" -> loadPaused(channel, device, args)

            "pause", "resume", "stop" -> {
                channel.control(command)
                println(channel.refresh())
            }

            "seek" -> {
                val seconds = args.getOrNull(2)?.toDoubleOrNull() ?: error("Seek needs seconds")
                require(seconds.isFinite() && seconds >= 0) { "Invalid seek position" }
                channel.control("seek", seconds)
                println(channel.refresh())
            }

            else -> error("Unknown command")
        }
    }
}

private fun loadPaused(channel: CastConnection, device: CastDevice, args: Array<String>) {
    val holdSeconds = args.getOrNull(2)?.toIntOrNull() ?: DEFAULT_HOLD_SECONDS
    require(holdSeconds in MIN_HOLD_SECONDS..MAX_HOLD_SECONDS) { "Hold must be 30 to 3600 seconds" }
    // Read the scoped upstream URL from stdin, keeping it out of process arguments.
    val upstream = URI.create(readln().trim())
    require(upstream.scheme in listOf("http", "https") && upstream.host != null && upstream.userInfo == null) {
        "A reachable media URL without embedded credentials is required"
    }
    CastMediaRelay(device.address, upstream).use { relay ->
        channel.load(relay.url, "Media", autoplay = false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(holdSeconds.toLong())
        while (System.nanoTime() < deadline) {
            println(channel.refresh())
            Thread.sleep(STATUS_POLL_MS)
        }
    }
}

private const val MIN_HOLD_SECONDS = 30
private const val DEFAULT_HOLD_SECONDS = 45
private const val MAX_HOLD_SECONDS = 3_600
private const val STATUS_POLL_MS = 2_000L
