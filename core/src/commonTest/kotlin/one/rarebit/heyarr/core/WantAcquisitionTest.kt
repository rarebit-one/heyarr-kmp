package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.heyarr.DesiredItemJson
import one.rarebit.heyarr.core.heyarr.WantAcquisitionJson
import one.rarebit.heyarr.core.heyarr.fanOutLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two-site half of acquisition status (heyarr-core ADR-0119, #799, #924) and how it is worded. */
class WantAcquisitionTest {

    // One line each once joined: JsonScan is a field scanner, and the fixtures stay on one line.
    private val atSibling =
        """{"desired_item_id":"d1","phase":"idle","state":"MISSING",""" +
            """"fan_out":{"origin_site":"site-a","op_hash":"blake3:aa","infohash":"0123",""" +
            """"release_name":"Sintel 2010 1080p","scope":"work","aspect":"primary",""" +
            """"outcome":"no_site_source","detail":"a private release publishes no trackers",""" +
            """"applied_at":"2026-11-21T08:00:00Z","reported_unsourced":true,""" +
            """"peer_pull":[{"op_hash":"blake3:bb","blob_hash":"blake3:cc","outcome":"pulled",""" +
            """"applied_at":"2026-11-21T09:00:00Z"}]}}"""

    private val atOrigin =
        """{"desired_item_id":"d2","phase":"downloading","state":"DOWNLOADING",""" +
            """"transfer":{"provider":"qbittorrent","external_id":"0123",""" +
            """"release_name":"Sintel 2010 1080p","bytes_total":10,"bytes_done":5,"percent_done":50},""" +
            """"published":{"op_hash":"blake3:aa","infohash":"0123",""" +
            """"release_name":"Sintel 2010 1080p","scope":"work","aspect":"primary","private":false,""" +
            """"selected_at":"2026-11-21T07:00:00Z","unsourced_by":[{"site":"site-c",""" +
            """"op_hash":"blake3:dd","reported_at":"2026-11-21T08:30:00Z"}],""" +
            """"holds":[{"op_hash":"blake3:ee","blob_hash":"blake3:ff","scope":"work",""" +
            """"held_at":"2026-11-21T09:00:00Z"}],"siblings":[{"peer":"peer-b","peer_id":"p-b",""" +
            """"site":"site-b","reachable":true,"outcome":"applied",""" +
            """"applied_at":"2026-11-21T07:05:00Z","state":"DOWNLOADING","reported_unsourced":false,""" +
            """"asked_at":"2026-11-21T09:10:00Z"},{"peer":"peer-c","peer_id":"p-c","reachable":false,""" +
            """"outcome":"unknown","detail":"unreachable: connection refused",""" +
            """"reported_unsourced":false,"asked_at":"2026-11-21T09:10:00Z"},{"peer":"peer-d",""" +
            """"peer_id":"p-d","site":"site-d","reachable":true,"outcome":"pending",""" +
            """"reported_unsourced":false,"asked_at":"2026-11-21T09:10:00Z"}]}}"""

    @Test
    fun aSiblingsWantSaysWhereItCameFromAndWhatHappenedHere() {
        val a = assertNotNull(WantAcquisitionJson.parse(atSibling))
        val f = assertNotNull(a.fanOut)
        assertEquals("site-a", f.originSite)
        assertEquals("no_site_source", f.outcome)
        assertTrue(f.reportedUnsourced)
        assertNull(a.published)
        assertEquals(
            listOf(
                "From site site-a's choice: Sintel 2010 1080p",
                "Here: no site source — a private release publishes no trackers",
                "Reported to site site-a: held there for this site to pull",
            ),
            a.lines(),
        )
    }

    @Test
    fun theOriginSaysWhatEachSiblingDid() {
        val a = assertNotNull(WantAcquisitionJson.parse(atOrigin))
        assertNull(a.fanOut)
        val p = assertNotNull(a.published)
        assertEquals(listOf("site-c"), p.unsourcedBy)
        assertEquals(3, p.siblings.size)
        assertFalse(p.siblings[1].reachable)
        assertEquals(
            listOf(
                "Published to siblings: Sintel 2010 1080p",
                "Site site-b: applied · downloading",
                // Said, not guessed: the sibling could not be asked.
                "peer-c: unknown (unreachable)",
                "Site site-d: pending",
                "Site site-c could not source this; held for it to pull",
            ),
            a.lines(),
        )
    }

    @Test
    fun aSingleSiteWantShowsNothing() {
        val a =
            assertNotNull(WantAcquisitionJson.parse("""{"desired_item_id":"d3","phase":"idle","state":"MISSING"}"""))
        assertNull(a.fanOut)
        assertNull(a.published)
        assertEquals(emptyList(), a.lines())
        val want = DesiredItemJson.list(
            """{"items":[{"id":"d3","work_id":"w","monitor":true,"acquisition":{"state":"MISSING"}}]}""",
        ).single()
        assertEquals(emptyList(), fanOutLines(want, a))
        assertEquals(emptyList(), fanOutLines(want, null))
    }

    @Test
    fun theWantListsOriginShowsBeforeTheStatusLoads() {
        val want = DesiredItemJson.list(
            """{"items":[{"id":"d1","work_id":"w","monitor":true,"origin_site":"site-a",""" +
                """"acquisition":{"state":"MISSING"}}]}""",
        ).single()
        assertEquals("site-a", want.originSite)
        assertEquals(listOf("From site site-a's choice"), fanOutLines(want, null))
        // Once the status has loaded, its own fan-out lines replace the bare origin.
        val lines = fanOutLines(want, WantAcquisitionJson.parse(atSibling))
        assertEquals("From site site-a's choice: Sintel 2010 1080p", lines.first())
        assertEquals(1, lines.count { it.startsWith("From site") })
    }
}
