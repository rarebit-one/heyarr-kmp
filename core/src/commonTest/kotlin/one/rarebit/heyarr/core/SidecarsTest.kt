package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.core.heyarr.SidecarResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The sidecar levers send a work-scoped body and read the node's own counts back (ADR-0084/0085/0087). */
class SidecarsTest {
    @Test
    fun bodiesAreScopedToTheWork() {
        assertEquals("""{"work_id":"w1"}""", SidecarAction.EXTRACT_CAPTIONS.body("w1"))
        assertEquals("""{"work_id":"w1","language":"en"}""", SidecarAction.FETCH_CAPTIONS.body("w1", "en"))
        assertEquals("""{"work_id":"w1"}""", SidecarAction.FETCH_COVER.body("w1", "en"))
        assertFailsWith<IllegalArgumentException> { SidecarAction.FETCH_CAPTIONS.body("w1") }
    }

    @Test
    fun eachLeverReadsItsOwnCount() {
        val want = SidecarResult.parse(SidecarAction.FETCH_CAPTIONS, """{"candidates":5,"created":3}""", "en")
        assertEquals(3, want.queued)
        assertEquals("Fetching EN captions for 3 videos from the subtitle provider.", want.message)
        val extract = SidecarResult.parse(SidecarAction.EXTRACT_CAPTIONS, """{"candidates":1,"enqueued":1}""")
        assertEquals("Extracting embedded captions from 1 video.", extract.message)
    }

    @Test
    fun nothingQueuedSaysWhy() {
        assertEquals(
            "EN captions are already wanted for every video here.",
            SidecarResult.parse(SidecarAction.FETCH_CAPTIONS, """{"candidates":2,"created":0}""", "en").message,
        )
        assertEquals(
            "Every video here already has EN captions.",
            SidecarResult.parse(SidecarAction.FETCH_CAPTIONS, """{"candidates":0,"created":0}""", "en").message,
        )
        assertEquals(
            "This work already has a cover and a canonical id.",
            SidecarResult.parse(SidecarAction.FETCH_COVER, """{"candidates":0,"enqueued":0}""").message,
        )
    }
}
