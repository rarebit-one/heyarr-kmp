package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.discovery.Discovery
import one.rarebit.heyarr.core.discovery.DiscoverySource
import one.rarebit.heyarr.core.discovery.MdnsHit
import one.rarebit.heyarr.core.discovery.MdnsResolver
import one.rarebit.heyarr.core.discovery.NoMdnsResolver
import one.rarebit.heyarr.core.discovery.NodeDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure discovery fallback chain: mDNS hit → DNS name → manual entry, and the base-URL derivation. */
class DiscoveryTest {

    private fun resolver(hit: MdnsHit?) = MdnsResolver { hit }

    @Test fun mdnsHitWinsAndDerivesTlsBaseUrl() {
        val d = NodeDiscovery(resolver(MdnsHit(host = "node-a.local", port = 7777, tls = true)))
        val r = d.discover(manual = "https://typed.example:7777")
        assertEquals(DiscoverySource.MDNS, r.source)
        assertEquals("https://node-a.local:7777", r.baseUrl)
    }

    @Test fun mdnsPlaintextHitDerivesHttpBaseUrl() {
        val d = NodeDiscovery(resolver(MdnsHit(host = "node.local", port = 8080, tls = false)))
        val r = d.discover()
        assertEquals(DiscoverySource.MDNS, r.source)
        assertEquals("http://node.local:8080", r.baseUrl)
    }

    @Test fun noMdnsFallsBackToDnsName() {
        val d = NodeDiscovery(NoMdnsResolver, dnsHost = "heyarr.example.test")
        val r = d.discover(manual = "https://typed.example:7777")
        // DNS name is preferred over the saved manual value — the user is not asked to type
        // while a well-known name is available.
        assertEquals(DiscoverySource.DNS, r.source)
        assertEquals("https://heyarr.example.test:${Discovery.DEFAULT_PORT}", r.baseUrl)
    }

    @Test fun noDnsNameIsBuiltIn() {
        // A build configured with no DNS name skips that rung: the saved URL stands.
        val r = NodeDiscovery(NoMdnsResolver).discover(manual = "https://my-node.lan:7777")
        assertEquals(DiscoverySource.MANUAL, r.source)
        assertEquals("https://my-node.lan:7777", r.baseUrl)
    }

    @Test fun noMdnsNoDnsFallsBackToManual() {
        val d = NodeDiscovery(NoMdnsResolver, dnsHost = "")
        val r = d.discover(manual = "https://my-node.lan:7777")
        assertEquals(DiscoverySource.MANUAL, r.source)
        assertEquals("https://my-node.lan:7777", r.baseUrl)
    }

    @Test fun nothingAvailableFindsNothing() {
        val r = NodeDiscovery(NoMdnsResolver, dnsHost = "").discover(manual = null)
        assertEquals(DiscoverySource.MANUAL, r.source)
        assertEquals("", r.baseUrl)
    }

    @Test fun blankManualIsNotChosen() {
        val d = NodeDiscovery(NoMdnsResolver, dnsHost = "")
        val r = d.discover(manual = "   ")
        assertEquals(DiscoverySource.MANUAL, r.source)
        assertEquals("", r.baseUrl)
    }
}
