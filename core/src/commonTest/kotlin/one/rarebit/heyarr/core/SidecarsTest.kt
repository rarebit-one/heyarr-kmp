package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.core.heyarr.SidecarClient
import one.rarebit.heyarr.core.heyarr.SidecarReply
import one.rarebit.heyarr.core.heyarr.SidecarResult
import one.rarebit.heyarr.core.mcp.McpTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun candidatesWithNothingQueuedAreAlreadyPendingNotDone() {
        assertEquals(
            "Caption extraction is already queued for every video here.",
            SidecarResult.parse(SidecarAction.EXTRACT_CAPTIONS, """{"candidates":2,"enqueued":0}""").message,
        )
        assertEquals(
            "A cover lookup is already queued for this work.",
            SidecarResult.parse(SidecarAction.FETCH_COVER, """{"candidates":1,"enqueued":0}""").message,
        )
    }

    @Test
    fun guestsGetCaptionsButNotCoverLookups() {
        assertTrue(SidecarAction.FETCH_CAPTIONS.allowedFor(ClientMode.GUEST))
        assertTrue(SidecarAction.EXTRACT_CAPTIONS.allowedFor(ClientMode.GUEST))
        assertFalse(SidecarAction.FETCH_COVER.allowedFor(ClientMode.GUEST))
        assertTrue(SidecarAction.FETCH_COVER.allowedFor(ClientMode.ENROLLED))
    }

    @Test
    fun aForbiddenAnswerKeepsTheNodesReason() {
        val reply = SidecarClient.reply(
            SidecarAction.FETCH_CAPTIONS,
            403,
            """{"title":"Forbidden","detail":"this credential lacks the write scope","code":"scope_missing"}""",
            "en",
        )
        assertEquals(
            SidecarReply.Refused(
                "this credential lacks the write scope",
                403,
                tool = "POST /api/v1/subtitles/want",
                code = "scope_missing",
            ),
            reply,
        )
        assertEquals(
            SidecarReply.Done(SidecarResult(SidecarAction.EXTRACT_CAPTIONS, candidates = 2, queued = 2)),
            SidecarClient.reply(SidecarAction.EXTRACT_CAPTIONS, 202, """{"candidates":2,"enqueued":2}"""),
        )
        assertFailsWith<McpTransportException> { SidecarClient.reply(SidecarAction.FETCH_COVER, 401, "") }
    }
}
