package one.rarebit.heyarr.vault

import one.rarebit.heyarr.core.net.JsonScan
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Cross-language KAT for [SnapshotEnvelope] against heyarr-core's Go-generated
 * `snapshot_envelope.json` (heyarr-core#681), copied byte-for-byte. `seal` pins the envelope
 * bytes a producer would encrypt; `open` pins the verdict for a decrypted plaintext presented
 * under a (space, frontier) — including every relabelling a keyless `write` holder could try.
 */
class SnapshotEnvelopeTest {
    private val root: String by lazy {
        val body = SnapshotEnvelopeTest::class.java.getResourceAsStream("/vault/snapshot_envelope.json")
            ?.readBytes()?.decodeToString() ?: fail("missing test resource")
        JsonScan.rootObject(body)!!
    }

    private fun b64(s: String?): ByteArray = Base64.getDecoder().decode(s ?: "")

    /** The string elements of the array at [key], `null` meaning empty, empties kept. */
    private fun strings(obj: String, key: String): List<String> {
        val arr = JsonScan.arrayOf(obj, listOf(key)) ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arr).map { it.groupValues[1] }.toList()
    }

    private fun cases(key: String): List<String> =
        JsonScan.objectsOf(JsonScan.arrayOf(root, listOf(key)) ?: "[]", emptyList())

    @Test
    fun sealMatchesGo() {
        val seals = cases("seal")
        assertTrue(seals.size >= 3, "seal vectors missing")
        for (v in seals) {
            val name = JsonScan.stringField(v, "name")
            val got = SnapshotEnvelope.seal(
                JsonScan.stringField(v, "space_id")!!,
                strings(v, "frontier"),
                b64(JsonScan.stringField(v, "state_b64")),
            )
            assertContentEquals(b64(JsonScan.stringField(v, "envelope_b64")), got, "seal/$name")
        }
    }

    @Test
    fun openReachesGosVerdict() {
        val opens = cases("open")
        assertTrue(opens.size >= 8, "open vectors missing")
        val verdicts = HashSet<String>()
        for (v in opens) {
            val name = JsonScan.stringField(v, "name")
            val want = JsonScan.stringField(v, "verdict")!!
            verdicts.add(want)
            val opened = SnapshotEnvelope.open(
                JsonScan.stringField(v, "presented_space_id")!!,
                strings(v, "presented_frontier"),
                b64(JsonScan.stringField(v, "plaintext_b64")),
            )
            val (got, state) = when (opened) {
                is SnapshotEnvelope.Opened.Authenticated -> "authenticated" to opened.state
                is SnapshotEnvelope.Opened.Legacy -> "legacy" to opened.state
                is SnapshotEnvelope.Opened.Refused -> "refused" to null
            }
            assertEquals(want, got, "open/$name")
            if (state != null) assertContentEquals(b64(JsonScan.stringField(v, "state_b64")), state, "open/$name state")
        }
        assertEquals(setOf("authenticated", "legacy", "refused"), verdicts, "vectors must cover every verdict")
    }
}
