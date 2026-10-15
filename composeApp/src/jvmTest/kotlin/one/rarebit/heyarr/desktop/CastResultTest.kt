package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.desktop.heyarr.CastResult
import one.rarebit.heyarr.desktop.heyarr.PlayAlternative
import one.rarebit.heyarr.desktop.heyarr.parsePlayResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `POST /api/v1/renderers/{udn}/play` answers, read the way the cast picker needs them (ADR-0111). */
class CastResultTest {
    @Test
    fun `a direct route says whether the app may close`() {
        val body = """{"session_id":"s1","route":"r2","client_must_stay_connected":false,""" +
            """"reason_code":"route-r2-within-capacity"}"""
        assertEquals(CastResult.Success(clientMustStayConnected = false), parsePlayResponse(body, 200))
        assertEquals(
            CastResult.Success(clientMustStayConnected = true),
            parsePlayResponse("""{"client_must_stay_connected":true}""", 200),
        )
    }

    @Test
    fun `a route refusal carries its reason and the alternatives`() {
        val body = """{"type":"about:blank","title":"Conflict","status":409,""" +
            """"detail":"the 4K copy needs 120 Mbit/s; the link between site-a and site-b carries 80",""" +
            """"code":"route-r2-over-capacity","alternatives":[""" +
            """{"kind":"smaller_copy","asset_id":"a-1080","height_px":1080,"bitrate_kbps":8123.5},""" +
            """{"kind":"copy_to_site_first","site":"site-b"}]}"""
        val r = parsePlayResponse(body, 409)
        assertTrue(r is CastResult.Conflict, "got $r")
        assertEquals("route-r2-over-capacity", r.code)
        assertEquals("the 4K copy needs 120 Mbit/s; the link between site-a and site-b carries 80", r.reasonHuman)
        assertEquals(
            listOf(
                PlayAlternative("smaller_copy", "a-1080", 1080, 8123, null),
                PlayAlternative("copy_to_site_first", null, null, null, "site-b"),
            ),
            r.alternatives,
        )
    }

    @Test
    fun `a codec refusal stays a plain refusal so the picker can offer cast anyway`() {
        val body = """{"title":"Conflict","status":409,"detail":"this asset needs TRANSCODE on this device"}"""
        val r = parsePlayResponse(body, 409)
        assertTrue(r is CastResult.Refused, "got $r")
        assertEquals(409, r.code)
    }

    @Test
    fun `a renderer's own refusal is passed through`() {
        val r = parsePlayResponse("""{"title":"Bad Gateway","status":502,"detail":"714 Illegal MIME-type"}""", 502)
        assertTrue(r is CastResult.Refused, "got $r")
        assertTrue("714" in r.message, r.message)
    }
}
