package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.cast.CastDiscovery
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.jmdns.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastDiscoveryTest {
    private fun ip(last: Int) = InetAddress.getByAddress(byteArrayOf(10, 0, 0, last.toByte())) as Inet4Address

    // ServiceInfo.create() has no public way to attach an address, and castDevice() drops a record
    // without one, so reach the package-private setter the JmDNS resolver itself uses.
    private fun service(id: String, name: String, at: Inet4Address): ServiceInfo =
        ServiceInfo.create("_googlecast._tcp.local.", "svc-$id", 8009, 0, 0, mapOf("id" to id, "fn" to name)).also {
            it.javaClass.getDeclaredMethod("addAddress", Inet4Address::class.java)
                .apply { isAccessible = true }.invoke(it, at)
        }

    @Test
    fun slowInterfacesAreBrowsedSideBySide() {
        val interfaces = (1..4).map(::ip)
        // Every browse blocks until all four have started: a sequential walk would never get past
        // the first, so finishing at all (well inside the latch timeout) proves they overlapped.
        val started = CountDownLatch(interfaces.size)
        val window = 400L
        val discovery = CastDiscovery(
            browse = { address ->
                started.countDown()
                check(started.await(5, TimeUnit.SECONDS)) { "browses ran one after another" }
                Thread.sleep(window)
                listOf(service("id-${address.address[3]}", "TV ${address.address[3]}", address))
            },
            addresses = { interfaces },
        )
        val began = System.nanoTime()
        val devices = discovery.discover()
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
        assertEquals(listOf("TV 1", "TV 2", "TV 3", "TV 4"), devices.map { it.name })
        // ≈ one window, not four (sequential would be ≥ 1600 ms).
        assertTrue(elapsed < window * 3, "took $elapsed ms for ${interfaces.size} interfaces")
    }

    @Test
    fun duplicatesKeepTheFirstInterfacesRecordAndFailuresAreSkipped() {
        val (wifi, wired, broken) = listOf(ip(1), ip(2), ip(3))
        val threads = ConcurrentHashMap.newKeySet<String>()
        val discovery = CastDiscovery(
            browse = { address ->
                threads += Thread.currentThread().name
                when (address) {
                    // The later interface answers first; the earlier one still wins the dedupe,
                    // exactly as the old sequential walk had it.
                    wifi -> Thread.sleep(150).let { listOf(service("same", "Lounge", wifi)) }

                    wired -> listOf(service("same", "Lounge", wired), service("other", "Den", wired))

                    else -> error("interface went away mid-browse")
                }
            },
            addresses = { listOf(wifi, wired, broken) },
        )
        val devices = discovery.discover()
        assertEquals(listOf("Den", "Lounge"), devices.map { it.name })
        assertEquals(wifi, devices.single { it.id == "same" }.address)
        assertTrue(threads.all { it == "heyarr-cast-discovery" }, "browsed on $threads")
    }

    @Test
    fun noUsableInterfaceFindsNothing() {
        val discovery = CastDiscovery(browse = { error("not called") }, addresses = { emptyList() })
        assertEquals(emptyList(), discovery.discover())
    }
}
