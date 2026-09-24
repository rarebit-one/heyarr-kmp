package one.rarebit.heyarr.core.contract

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The hand-written wire parsers, held to heyarr-core's OpenAPI contract.
 *
 * Every heyarr response in this repo is read by hand on `JsonScan` (no serialization
 * library, by design), so a field the server renamed or never sent reads back as null and
 * the UI quietly shows less. This test extracts the JSON keys each parser reads from its
 * source ([ParserSource]) — in every module, `:androidApp` included, as text — and fails
 * when a read names no field that the parser's endpoint declares in the vendored spec
 * (`src/jvmTest/resources/openapi/`, refreshed by `scripts/refresh-openapi-spec.sh`).
 *
 * To keep it honest in both directions it also fails when: a mapped parser or spec target
 * disappears; an allowance no longer excuses anything; or a new top-level declaration reads
 * keys without being either mapped or listed as out of scope. When it fails, fix the parser,
 * or — for a genuine spec gap — add an [Allowance] with the reason and get the spec fixed
 * in heyarr-core.
 */
class OpenApiContractTest {

    private companion object {
        /** Parser declaration (top-level object/class/fun, by simple name, in any module) → spec targets. */
        val MAPPING: Map<String, List<String>> = mapOf(
            // :core — REST reads and the telemetry screens.
            "QualityProfileJson" to listOf("GET /api/v1/quality-profiles"),
            "DesiredItemJson" to listOf("GET /api/v1/desired", "GET /api/v1/desired/{id}", "POST /api/v1/desired"),
            "CandidateJson" to listOf("GET /api/v1/desired/{id}/candidates"),
            "Problem" to listOf("#Problem"),
            "SessionInfoJson" to listOf("GET /api/v1/session"),
            "ProviderJson" to listOf("GET /api/v1/providers"),
            "CapabilitiesJson" to listOf("GET /api/v1/capabilities"),
            "LibraryInfoJson" to listOf("GET /api/v1/libraries"),
            "JobJson" to listOf("GET /api/v1/jobs"),
            "ContinueJson" to listOf("GET /api/v1/consumption/continue"),
            "FollowedSourcesJson" to listOf("GET /api/v1/followed-sources", "GET /api/v1/followed-sources/{id}"),
            "FollowedItemsJson" to listOf("GET /api/v1/followed-sources/{id}/items"),
            // MCP tools whose result IS a REST resource: get_content_satisfaction returns the
            // same `resources.Satisfaction` as the REST route, and every scorer's reasons are §63's.
            "SatisfactionJson" to listOf("GET /api/v1/desired/{id}/satisfaction"),
            "ReasonJson" to listOf("#EvaluationReason"),
            "McpClient" to listOf("POST /api/v1/mcp"),
            // :composeApp / :androidApp.
            "WorksJson" to listOf("GET /api/v1/works", "GET /api/v1/works/{id}"),
            "WorkDetailJson" to listOf(
                "GET /api/v1/works/{id}",
                "GET /api/v1/works/{id}/assets",
                "GET /api/v1/assets/{id}",
                "GET /api/v1/editions/{id}",
                "GET /api/v1/desired",
            ),
            "TracksJson" to listOf("GET /api/v1/works/{id}/assets"),
            "GroupingJson" to listOf("GET /api/v1/artists", "GET /api/v1/authors"),
            "MusicJson" to listOf("GET /api/v1/artists"),
            "MusicClient" to listOf("GET /api/v1/artists"),
            "ContinueClient" to listOf("GET /api/v1/consumption/continue"),
            "SessionJson" to listOf("GET /api/v1/session"),
            "SearchResultsJson" to listOf("POST /api/v1/search"),
            "DiscoverClient" to listOf("POST /api/v1/discover"),
            "HeyarrApi" to listOf("POST /api/v1/playback/plan"),
            "PlaybackJson" to listOf("POST /api/v1/playback/plan", "POST /api/v1/playback"),
            "ConsumptionClient" to listOf("POST /api/v1/consumption/sessions", "POST /api/v1/devices"),
            "EnrolClient" to listOf("POST /enrol", "#Problem"),
            "VaultSpaceClient" to SPACES,
            "PersonalStateClient" to SPACES,
            "EncryptedChange" to listOf("#EncryptedChange"),
            "EncryptedSnapshot" to listOf("#EncryptedSnapshot"),
            "JdkVaultBlobStore" to listOf("PUT /api/v1/vault/blobs/{hash}"),
        )

        val SPACES
            get() = listOf(
                "GET /api/v1/spaces",
                "POST /api/v1/spaces",
                "GET /api/v1/spaces/{id}/keys",
                "GET /api/v1/spaces/{id}/changes",
                "POST /api/v1/spaces/{id}/changes",
                "GET /api/v1/spaces/{id}/snapshot",
            )

        /** Declarations that read JSON keys but not from a heyarr response openapi.yaml describes. */
        val OUT_OF_SCOPE: Map<String, String> = buildMap {
            val mcp = "an MCP tool result: openapi.yaml types JSON-RPC `result` as a free-form object and the tools " +
                "publish no outputSchema, so there is no schema to hold it to"
            listOf(
                "WantJson", "WantCreatedJson", "ExplanationJson", "RendererJson", "PlaybackStatusJson", "PeerJson",
                "ReplicaJson", "SearchHitsJson", "QueuedJobJson", "DiscoveryJson", "ExternalIdJson",
            ).forEach { put(it, mcp) }
            val crdt = "decrypted personal-state / vault payload (voidbind-encrypted; the server only sees ciphertext)"
            listOf(
                "PlayChange", "PlayLog", "Playlist", "PlaylistChange", "PositionChange", "ReadingPositions",
                "StarChange", "StarSet", "Drive", "VaultFrame",
            ).forEach { put(it, crdt) }
            val local = "a file this app writes and reads itself"
            listOf("DaemonConfig", "FileSettingsStore", "FileSyncIndexStore", "RecentSearches", "ReaderPosition")
                .forEach { put(it, local) }
            put("ExternalMetadata", "its own on-disk cache of third-party metadata")
            put("VaultSyncDaemon", "requests on its own local control socket")
            put("ExternalParsers", "third-party public metadata APIs, not heyarr")
            put("LandscapeArtwork", "third-party artwork API, not heyarr")
            put("PlayerEvents", "libmpv JSON IPC events, not heyarr")
            put("Fixtures", "preview/screenshot fixtures")
            put("FakeHeyarrTransport", "preview fake: reads the REQUEST it is sent, not a response")
        }

        // Every spec gap and client drift the test first found (#88) is fixed: the spec gaps in heyarr-core
        // (declared since the vendored commit) and the dead client reads deleted. What remains is
        // structurally unverifiable: keys inside free-form objects.
        val ALLOWANCES: List<Allowance> = listOf(
            Allowance("WorksJson", listOf("artist", "author"), ATTRS),
            Allowance("ContinueClient", listOf("season", "episode"), ATTRS),
            Allowance(
                "McpClient",
                listOf("content", "text", "isError", "tool"),
                "MCP PROTOCOL — CallToolResult inside JSON-RPC `result` and the error's `data`, both free-form " +
                    "in the spec",
            ),
        )

        const val ATTRS = "FREE-FORM — keys inside a work's `attributes`, which the spec types as an open object"

        val INDEX: OpenApiIndex by lazy {
            val resource = OpenApiContractTest::class.java.getResource("/openapi/heyarr-core-openapi.json")
            val json = requireNotNull(resource) {
                "vendored spec missing — run scripts/refresh-openapi-spec.sh"
            }.readText()
            OpenApiIndex.load(json)
        }
        val READS: List<KeyRead> by lazy { RepoSources.reads() }
        val CHECK by lazy { ContractCheck(INDEX, MAPPING, ALLOWANCES) }
    }

    @Test
    fun `every parser reads only fields its endpoint declares`() {
        val v = CHECK.violations(READS)
        val dead = CHECK.deadAliases(READS)
        val checked = READS.filter { it.declaration in MAPPING }
        val parsers = checked.map { it.file to it.declaration }.toSet().size
        val keys = checked.flatMap { it.keys }.toSet().size
        println(
            "OpenApiContractTest: ${checked.size} key reads in $parsers parser declarations ($keys distinct keys) " +
                "checked; " +
                "${READS.size} key reads found in all main sources",
        )
        // Dead aliases are informational; set OPENAPI_CONTRACT_VERBOSE=1 to list them.
        if (dead.isNotEmpty()) {
            val verbose = System.getenv("OPENAPI_CONTRACT_VERBOSE") != null
            val list = if (verbose) ":\n  " + dead.joinToString("\n  ") else ""
            println("OpenApiContractTest: ${dead.size} tolerant-read alias(es) the spec never sends (not failing)$list")
        }
        if (v.isNotEmpty()) {
            fail(
                "${v.size} parser read(s) name no field the spec declares — fix the parser, or allow a " +
                    "genuine spec gap (with its reason) in OpenApiContractTest.ALLOWANCES:\n  " +
                    v.joinToString("\n  "),
            )
        }
    }

    @Test
    fun `every mapped spec target still exists`() {
        val missing = CHECK.missingTargets()
        assertTrue(missing.isEmpty(), "spec targets that are gone:\n  " + missing.joinToString("\n  "))
    }

    @Test
    fun `every mapped or out-of-scope parser still exists`() {
        val declared = READS.map { it.declaration }.toSet()
        val gone = (MAPPING.keys + OUT_OF_SCOPE.keys).filter { it !in declared }
        assertTrue(gone.isEmpty(), "no longer found (renamed? removed? no key reads left?) — update the table: $gone")
    }

    @Test
    fun `every declaration that reads JSON keys is mapped or explicitly out of scope`() {
        val unmapped = READS.filter { it.declaration !in MAPPING && it.declaration !in OUT_OF_SCOPE }
            .groupBy({ it.declaration }, { it.file }).mapValues { it.value.distinct() }
        assertTrue(
            unmapped.isEmpty(),
            "new JSON readers — map each to its spec target(s) in OpenApiContractTest.MAPPING, or list it in " +
                "OUT_OF_SCOPE with the reason:\n  " + unmapped.entries.joinToString("\n  "),
        )
    }

    @Test
    fun `no allowance is stale`() {
        val stale = CHECK.staleAllowances(READS)
        assertTrue(stale.isEmpty(), "stale allowances:\n  " + stale.joinToString("\n  "))
    }

    @Test
    fun `the vendored spec records the heyarr-core commit it came from`() {
        val source = requireNotNull(javaClass.getResource("/openapi/heyarr-core-openapi.source")).readText()
        assertTrue(Regex("""(?m)^commit=[0-9a-f]{40}$""").containsMatchIn(source), "bad .source file:\n$source")
    }
}
