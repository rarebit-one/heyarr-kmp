package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The confirm half of provisional upload pins (heyarr-core ADR-0096 amendment, #714), driven
 * through the real [VaultSpaceClient] against a fake node: a change push that the node accepts or
 * refuses, and a confirm route that answers, fails, or is not there at all (a node that predates
 * it). The ordering is the property under test — confirm after accept, never before or instead.
 */
class UploadConfirmationsTest {

    private class Req(val method: String, val url: String, val body: String?)

    /** A node: accepts changes (echoing the id it was sent) unless told to refuse, and serves confirm. */
    private class FakeNode : HttpTransport {
        val requests = ArrayList<Req>()
        var changeRefusal: HttpResponse? = null
        var confirmAnswer: (List<String>) -> HttpResponse = { blobs ->
            HttpResponse(
                200,
                """{"results":[${blobs.joinToString(",") {
                    """{"blob":"$it","status":"confirmed"}"""
                }}]}""",
            )
        }

        override fun get(url: String, headers: Map<String, String>): HttpResponse = HttpResponse(404, "")

        override fun post(
            url: String,
            body: String?,
            contentType: String?,
            headers: Map<String, String>,
        ): HttpResponse {
            requests.add(Req("POST", url, body))
            return when {
                url.endsWith("/vault/blobs/confirm") -> confirmAnswer(JsonScan.stringArray(body!!, "blobs"))

                url.contains("/changes") ->
                    changeRefusal
                        ?: HttpResponse(201, """{"change_id":"${JsonScan.stringField(body!!, "change_id")}"}""")

                else -> HttpResponse(500, "")
            }
        }

        val confirms get() = requests.filter { it.url.endsWith("/vault/blobs/confirm") }
    }

    private val base = "https://node.example:7777"
    private val manifest = "blake3:" + "a".repeat(64)
    private val content = "blake3:" + "b".repeat(64)
    private val later = "blake3:" + "c".repeat(64)

    private fun client(node: FakeNode) = VaultSpaceClient(node, base, Credential.Bearer("heyarr_x_secret"))

    @Test
    fun confirmIsSentAfterTheNodeAcceptsTheChange() {
        val node = FakeNode()
        val c = client(node)
        val confirmations = UploadConfirmations(c)

        val id = confirmations.afterAccepted(listOf(manifest, content)) {
            c.pushChange("s", emptyList(), byteArrayOf(1, 2, 3), 0)
        }

        assertEquals(
            listOf("/spaces/s/changes?key_epoch=0", "/vault/blobs/confirm"),
            node.requests.map {
                it.url.removePrefix("$base/api/v1")
            },
        )
        assertEquals(listOf(manifest, content), JsonScan.stringArray(node.confirms.single().body!!, "blobs"))
        assertEquals(PersonalStateId.changeId("s", emptyList(), byteArrayOf(1, 2, 3)), id)
        assertTrue(confirmations.pending.isEmpty())
    }

    @Test
    fun aRefusedChangeConfirmsNothing() {
        // The epoch-mismatch refusal (#713) is the case the amendment names: the writer re-seals
        // and uploads new blobs, and the refused write's blobs must be left to expire.
        val node = FakeNode().apply {
            changeRefusal = HttpResponse(409, """{"code":"change_key_epoch_mismatch","title":"stale key epoch"}""")
        }
        val c = client(node)
        val confirmations = UploadConfirmations(c)

        val refused = assertFailsWith<VaultHttpException> {
            confirmations.afterAccepted(listOf(manifest, content)) { c.pushChange("s", emptyList(), byteArrayOf(1), 3) }
        }

        assertTrue(refused.isChangeKeyEpochMismatch)
        assertTrue(node.confirms.isEmpty(), "a refused change must not confirm its blobs")
        assertTrue(confirmations.pending.isEmpty())
    }

    @Test
    fun aNodeWithoutTheConfirmRouteDoesNotFailTheWrite() {
        val node = FakeNode().apply { confirmAnswer = { HttpResponse(404, "") } }
        val c = client(node)
        val logged = ArrayList<String>()
        val confirmations = UploadConfirmations(c) { logged.add(it) }

        confirmations.afterAccepted(listOf(manifest, content)) { c.pushChange("s", emptyList(), byteArrayOf(1), null) }

        assertEquals(1, node.confirms.size)
        // Its pins are all permanent: nothing is left pending, and nothing is worth a log line.
        assertTrue(confirmations.pending.isEmpty())
        assertTrue(logged.isEmpty(), logged.joinToString())
    }

    @Test
    fun aFailedConfirmKeepsTheWriteAndRetriesOnTheNextOne() {
        val node = FakeNode()
        val healthy = node.confirmAnswer
        node.confirmAnswer = { HttpResponse(503, "") }
        val c = client(node)
        val logged = ArrayList<String>()
        val confirmations = UploadConfirmations(c) { logged.add(it) }

        // The change landed: the write returns normally even though the confirm did not.
        confirmations.afterAccepted(listOf(manifest, content)) { c.pushChange("s", emptyList(), byteArrayOf(1), 0) }
        assertEquals(setOf(manifest, content), confirmations.pending)
        assertTrue(logged.single().contains("will retry"), logged.joinToString())

        node.confirmAnswer = healthy
        confirmations.afterAccepted(listOf(later)) { c.pushChange("s", emptyList(), byteArrayOf(2), 0) }

        assertEquals(listOf(manifest, content, later), JsonScan.stringArray(node.confirms.last().body!!, "blobs"))
        assertTrue(confirmations.pending.isEmpty())
    }

    @Test
    fun retryPendingResendsWithoutAWrite() {
        val node = FakeNode().apply { confirmAnswer = { throw java.io.IOException("connection reset") } }
        val c = client(node)
        val confirmations = UploadConfirmations(c)
        confirmations.accepted(listOf(manifest))
        assertEquals(setOf(manifest), confirmations.pending)

        node.confirmAnswer =
            { HttpResponse(200, """{"results":[{"blob":"$manifest","status":"already_permanent"}]}""") }
        confirmations.retryPending()

        assertTrue(confirmations.pending.isEmpty())
        assertEquals(2, node.confirms.size)
    }

    @Test
    fun anAnswerRetryingCannotChangeIsLoggedAndDropped() {
        val node = FakeNode().apply {
            confirmAnswer = {
                HttpResponse(
                    200,
                    """{"results":[{"blob":"$manifest","status":"not_held"},""" +
                        """{"blob":"$content","status":"confirmed"}]}""",
                )
            }
        }
        val c = client(node)
        val logged = ArrayList<String>()
        val confirmations = UploadConfirmations(c) { logged.add(it) }

        confirmations.accepted(listOf(manifest, content))

        assertTrue(confirmations.pending.isEmpty())
        assertTrue(logged.single().contains("not_held"), logged.joinToString())
    }

    @Test
    fun aBlobTheNodeLeavesUnansweredStaysPending() {
        val node = FakeNode().apply {
            confirmAnswer = { HttpResponse(200, """{"results":[{"blob":"$manifest","status":"confirmed"}]}""") }
        }
        val confirmations = UploadConfirmations(client(node))

        confirmations.accepted(listOf(manifest, content))

        assertEquals(setOf(content), confirmations.pending)
    }

    @Test
    fun aMalformedIdIsLeftOutRatherThanBlockingTheRest() {
        val node = FakeNode()
        val logged = ArrayList<String>()
        val confirmations = UploadConfirmations(client(node)) { logged.add(it) }

        confirmations.accepted(listOf("blake3:ABC", manifest))

        assertEquals(listOf(manifest), JsonScan.stringArray(node.confirms.single().body!!, "blobs"))
        assertTrue(confirmations.pending.isEmpty())
        assertTrue(logged.single().contains("blake3:ABC"), logged.joinToString())
    }

    @Test
    fun confirmBlobsSplitsAtTheRoutesCap() {
        val node = FakeNode()
        val blobs = (0..VaultSpaceClient.CONFIRM_MAX_BLOBS).map { "blake3:" + it.toString(16).padStart(64, '0') }

        val results = client(node).confirmBlobs(blobs)

        assertEquals(
            listOf(VaultSpaceClient.CONFIRM_MAX_BLOBS, 1),
            node.confirms.map {
                JsonScan.stringArray(it.body!!, "blobs").size
            },
        )
        assertEquals(blobs, results.map { it.blob })
        assertTrue(results.all { it.isPermanent })
    }

    @Test
    fun confirmBlobsRefusesAMalformedIdBeforeSending() {
        val node = FakeNode()
        assertFailsWith<IllegalArgumentException> { client(node).confirmBlobs(listOf("sha256:00")) }
        assertTrue(node.requests.isEmpty())
    }

    @Test
    fun confirmBlobsKeepsARefusalsStatus() {
        val node = FakeNode().apply { confirmAnswer = { HttpResponse(403, "") } }
        val e = assertFailsWith<VaultHttpException> { client(node).confirmBlobs(listOf(manifest)) }
        assertEquals(403, e.status)
    }
}
