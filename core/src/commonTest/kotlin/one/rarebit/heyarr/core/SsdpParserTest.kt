package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.mcp.SsdpParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SsdpParserTest {

    @Test
    fun parsesWellFormedResponse() {
        val raw = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            DATE: Mon, 01 Jan 2024 00:00:00 GMT
            EXT:
            LOCATION: http://192.0.2.1:1400/xml/device_description.xml
            SERVER: Linux/5.4.0 UPnP/1.0 Sonos/68.3-09095
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:RINCON_000E58A99CA001400::urn:schemas-upnp-org:device:MediaRenderer:1
        """.trimIndent()

        val r = SsdpParser.parseResponse(raw)
        assertNotNull(r)
        assertEquals("http://192.0.2.1:1400/xml/device_description.xml", r.location)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", r.st)
        assertEquals("uuid:RINCON_000E58A99CA001400::urn:schemas-upnp-org:device:MediaRenderer:1", r.usn)
    }

    @Test
    fun parsesResponseWithCrLfLineEndings() {
        val raw = "HTTP/1.1 200 OK\r\n" +
            "LOCATION: http://192.0.2.2:45317/x.xml\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "USN: uuid:device-a98d::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
            "\r\n"

        val r = SsdpParser.parseResponse(raw)
        assertNotNull(r)
        assertEquals("http://192.0.2.2:45317/x.xml", r.location)
    }

    @Test
    fun returnsNullWhenLocationMissing() {
        val raw = """
            HTTP/1.1 200 OK
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:device-a98d
        """.trimIndent()

        assertNull(SsdpParser.parseResponse(raw))
    }

    @Test
    fun returnsNullWhenUsnMissing() {
        val raw = """
            HTTP/1.1 200 OK
            LOCATION: http://192.0.2.1:1400/xml/device_description.xml
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
        """.trimIndent()

        assertNull(SsdpParser.parseResponse(raw))
    }

    @Test
    fun returnsNullForNonOkStatus() {
        val raw = """
            HTTP/1.1 404 Not Found
            LOCATION: http://192.0.2.1:1400/xml/device_description.xml
            ST: urn:schemas-upnp-org:device:MediaRenderer:1
            USN: uuid:device-a98d
        """.trimIndent()

        assertNull(SsdpParser.parseResponse(raw))
    }

    @Test
    fun returnsNullForNotifyMessage() {
        // NOTIFY has no status line with 200
        val raw = """
            NOTIFY * HTTP/1.1
            HOST: 239.255.255.250:1900
            NT: urn:schemas-upnp-org:device:MediaRenderer:1
            NTS: ssdp:alive
            LOCATION: http://192.0.2.1:1400/xml/device_description.xml
            USN: uuid:device-a98d
        """.trimIndent()

        assertNull(SsdpParser.parseResponse(raw))
    }

    @Test
    fun msearchRequestContainsRequiredHeaders() {
        val req = SsdpParser.msearchRequest()
        assertTrue(req.contains("M-SEARCH * HTTP/1.1"))
        assertTrue(req.contains("239.255.255.250:1900"))
        assertTrue(req.contains("\"ssdp:discover\""))
        assertTrue(req.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(req.contains("MX: 3"))
    }

    @Test
    fun msearchRequestHonoursMxParam() {
        val req = SsdpParser.msearchRequest(mx = 5)
        assertTrue(req.contains("MX: 5"))
    }

    @Test
    fun headerParsingIsCaseInsensitive() {
        val raw = """
            HTTP/1.1 200 OK
            location: http://192.0.2.3:1400/desc.xml
            st: urn:schemas-upnp-org:device:MediaRenderer:1
            usn: uuid:lower-case-device
        """.trimIndent()

        val r = SsdpParser.parseResponse(raw)
        assertNotNull(r)
        assertEquals("http://192.0.2.3:1400/desc.xml", r.location)
        assertEquals("uuid:lower-case-device", r.usn)
    }
}
