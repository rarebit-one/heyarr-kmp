package one.rarebit.heyarr.desktop.vault.daemon

import one.rarebit.heyarr.core.net.JsonScan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The status object is a wire CONTRACT (the merged homelab-ops MCP + Omarchy plugin reads it), so
 * these tests pin the exact shape: every required key present, `last_sync_at` / `last_error`
 * carrying a real JSON null when absent (not the string "null", not a dropped key), and the atomic
 * writer producing a file a reader can parse.
 */
class StatusFileTest {

    private fun sample(
        phase: String = "running",
        lastSyncAtMs: Long? = 1_600_000_000_000,
        lastPass: PassStats? = PassStats(pushed = 2, pulled = 1, conflicts = 0, deleted = 3, ms = 42),
        conflicts: List<String> = listOf("a/b.txt"),
        lastError: String? = null,
    ) = StatusSnapshot(
        phase = phase,
        folder = "/home/alarm/Vault",
        spaceId = "01a0ae3b-ca68-7165-9dbb-527425e2f380",
        controller = "https://heyarr.br.thesim.family:7777",
        device = "ed25519:2dbc0bedd3b838dc",
        watching = true,
        lastSyncAtMs = lastSyncAtMs,
        lastPass = lastPass,
        conflicts = conflicts,
        lastError = lastError,
        nowMs = 1_600_000_123_000,
    )

    @Test
    fun jsonCarriesEveryContractField() {
        val json = sample().toJson()
        assertEquals(1, JsonScan.longField(json, "schema")?.toInt())
        assertTrue(JsonScan.stringField(json, "ts")!!.endsWith("Z"), "ts must be RFC3339 UTC")
        assertEquals("running", JsonScan.stringField(json, "phase"))
        assertEquals("/home/alarm/Vault", JsonScan.stringField(json, "folder"))
        assertEquals("01a0ae3b-ca68-7165-9dbb-527425e2f380", JsonScan.stringField(json, "space_id"))
        assertEquals("https://heyarr.br.thesim.family:7777", JsonScan.stringField(json, "controller"))
        assertEquals("ed25519:2dbc0bedd3b838dc", JsonScan.stringField(json, "device"))
        assertEquals(true, JsonScan.boolField(json, "watching"))
        assertTrue(JsonScan.stringField(json, "last_sync_at")!!.endsWith("Z"))
        // last_pass nested object.
        val pass = JsonScan.objectAt(json, "last_pass")!!
        assertEquals(2, JsonScan.longField(pass, "pushed")?.toInt())
        assertEquals(1, JsonScan.longField(pass, "pulled")?.toInt())
        assertEquals(3, JsonScan.longField(pass, "deleted")?.toInt())
        assertEquals(42, JsonScan.longField(pass, "ms")?.toInt())
    }

    @Test
    fun lastPassNumbersArePresent() {
        val json = sample().toJson()
        assertTrue(json.contains("\"last_pass\":{"))
        assertTrue(json.contains("\"pushed\":2"))
        assertTrue(json.contains("\"pulled\":1"))
        assertTrue(json.contains("\"conflicts\":0"))
        assertTrue(json.contains("\"deleted\":3"))
        assertTrue(json.contains("\"ms\":42"))
    }

    @Test
    fun conflictsArrayCarriesPaths() {
        val json = sample(conflicts = listOf("x/y.pdf")).toJson()
        assertTrue(json.contains("\"conflicts\":[{\"path\":\"x/y.pdf\"}]"), json)
    }

    @Test
    fun emptyConflictsIsAnEmptyArray() {
        val json = sample(conflicts = emptyList()).toJson()
        assertTrue(json.contains("\"conflicts\":[]"), json)
    }

    @Test
    fun absentLastSyncAndErrorAreJsonNullNotStrings() {
        val json = sample(lastSyncAtMs = null, lastError = null).toJson()
        assertTrue(json.contains("\"last_sync_at\":null"), json)
        assertTrue(json.contains("\"last_error\":null"), json)
        // And never the quoted string "null".
        assertTrue(!json.contains("\"last_sync_at\":\"null\""))
        assertTrue(!json.contains("\"last_error\":\"null\""))
    }

    @Test
    fun presentErrorIsAQuotedString() {
        val json = sample(lastError = "node unreachable").toJson()
        assertEquals("node unreachable", JsonScan.stringField(json, "last_error"))
    }

    @Test
    fun missingLastPassStillEmitsZeroedObject() {
        val json = sample(lastPass = null).toJson()
        assertTrue(json.contains("\"last_pass\":{\"pushed\":0,\"pulled\":0,\"conflicts\":0,\"deleted\":0,\"ms\":0}"), json)
    }

    @Test
    fun atomicWriteRoundTrips() {
        val dir = Files.createTempDirectory("vault-status")
        try {
            val target = dir.resolve("vault-sync.json")
            StatusSnapshot.writeAtomically(target, sample().toJson())
            assertTrue(Files.exists(target))
            val text = Files.readString(target)
            assertEquals("running", JsonScan.stringField(text, "phase"))
            // Overwrite atomically again; still parses, no leftover temp file.
            StatusSnapshot.writeAtomically(target, sample(phase = "paused").toJson())
            assertEquals("paused", JsonScan.stringField(Files.readString(target), "phase"))
            assertTrue(!Files.exists(target.resolveSibling("vault-sync.json.tmp")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
