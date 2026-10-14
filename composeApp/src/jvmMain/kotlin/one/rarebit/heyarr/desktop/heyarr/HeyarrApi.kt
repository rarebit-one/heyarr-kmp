package one.rarebit.heyarr.desktop.heyarr

import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.feeds.FollowedSource
import one.rarebit.heyarr.core.feeds.FollowedSourcesJson
import one.rarebit.heyarr.core.heyarr.*
import one.rarebit.heyarr.core.heyarr.SidecarAction
import one.rarebit.heyarr.core.heyarr.SidecarClient
import one.rarebit.heyarr.core.heyarr.SidecarReply
import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.mcp.DiscoveryJson
import one.rarebit.heyarr.core.mcp.Explanation
import one.rarebit.heyarr.core.mcp.ExplanationJson
import one.rarebit.heyarr.core.mcp.ExternalId
import one.rarebit.heyarr.core.mcp.ExternalIdJson
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.mcp.McpClient
import one.rarebit.heyarr.core.mcp.McpOutcome
import one.rarebit.heyarr.core.mcp.McpTransportException
import one.rarebit.heyarr.core.mcp.PeerJson
import one.rarebit.heyarr.core.mcp.PeerStatus
import one.rarebit.heyarr.core.mcp.PlaybackStatus
import one.rarebit.heyarr.core.mcp.PlaybackStatusJson
import one.rarebit.heyarr.core.mcp.QueuedJob
import one.rarebit.heyarr.core.mcp.QueuedJobJson
import one.rarebit.heyarr.core.mcp.ReleaseToExplain
import one.rarebit.heyarr.core.mcp.Renderer
import one.rarebit.heyarr.core.mcp.RendererJson
import one.rarebit.heyarr.core.mcp.RendererListResult
import one.rarebit.heyarr.core.mcp.Replica
import one.rarebit.heyarr.core.mcp.ReplicaJson
import one.rarebit.heyarr.core.mcp.Satisfaction
import one.rarebit.heyarr.core.mcp.SatisfactionJson
import one.rarebit.heyarr.core.mcp.SearchHits
import one.rarebit.heyarr.core.mcp.SearchHitsJson
import one.rarebit.heyarr.core.mcp.SsdpScanner
import one.rarebit.heyarr.core.mcp.Want
import one.rarebit.heyarr.core.mcp.WantCreated
import one.rarebit.heyarr.core.mcp.WantCreatedJson
import one.rarebit.heyarr.core.mcp.WantJson
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.library.LibraryClient
import one.rarebit.heyarr.desktop.library.Work
import one.rarebit.heyarr.desktop.library.WorkDetail
import one.rarebit.heyarr.desktop.library.WorkDetailClient
import one.rarebit.heyarr.desktop.library.WorksJson
import one.rarebit.heyarr.desktop.music.Track
import one.rarebit.heyarr.desktop.music.TracksJson
import one.rarebit.heyarr.desktop.ssdp.JvmSsdpScanner
import java.io.IOException
import java.net.URLEncoder

/**
 * Where and how to play an asset, resolved from the playback plan: the URL to
 * open, plus the source's true runtime in seconds when the plan is a transcode
 * `stream` (null for a direct blob, where the player's own duration is right).
 *
 * A `mode: "stream"` plan is a fragmented MP4 the node repackages as it goes: no
 * `Content-Length`, no ranges, so mpv can only seek inside what it has already
 * demuxed — a scrub ahead of the cache does nothing. ADR-0069 gives that stream a
 * *restart* seek instead, and [streamBaseUrl] is what makes it reachable: the token
 * URL with no `start` param, from which [HeyarrApi.streamUrl] builds `?start=<s>`.
 * Null for a direct blob, which the blob endpoint already seeks natively (ADR-0013).
 */
data class PlaybackTarget(val url: String, val durationSeconds: Double? = null, val streamBaseUrl: String? = null) {
    /** True for a node-repackaged stream: a seek is a restart, not an offset. */
    val restartSeekable: Boolean get() = streamBaseUrl != null
}

private const val HTTP_OK_MIN = 200
private const val HTTP_OK_MAX = 299
private const val HTTP_CONFLICT = 409

/**
 * The one typed door to heyarr for every screen. Each method is a single MCP tool (named
 * in its doc line) or one of the three verified REST reads; the mapping is exact and
 * nothing here invents an endpoint. Results are values; a tool's refusal comes back as
 * [McpOutcome.Refused] where the screen must show it, or is thrown where a refusal can
 * only mean failure. Blocking — call on `Dispatchers.IO`.
 */
class HeyarrApi(private val http: HttpTransport, val baseUrl: String, private val credential: Credential) {
    private val mcp = McpClient(http, baseUrl, credential)
    private val library = LibraryClient(http, baseUrl, credential)
    private val detail = WorkDetailClient(http, baseUrl, credential)

    // ── search & discovery ───────────────────────────────────────────────────────

    /** `search_content` — library works by title, optionally narrowed to one [type]. */
    fun searchContent(query: String, type: MediaType? = null, limit: Int = 60): SearchHits {
        val args = linkedMapOf<String, Any?>("query" to query.ifBlank { null }, "limit" to limit)
        type?.apiName?.let { args["content_type"] = it }
        return SearchHitsJson.parse(mcp.call("search_content", args).require())
    }

    /** `search_content` with only a content type — everything the library holds of that kind. */
    fun listByType(type: MediaType, limit: Int = 200): SearchHits = SearchHitsJson.parse(
        mcp.call("search_content", mapOf("content_type" to type.apiName, "limit" to limit)).require(),
    )

    /** `discover_content` — ask the metadata provider (TVDB) for series the library does NOT hold. A node with no provider refuses; that refusal is returned for the UI to show. */
    fun discover(query: String): McpResult<List<DiscoveryHit>> =
        mcp.call("discover_content", mapOf("query" to query)).map { DiscoveryJson.list(it) }

    // ── wants ────────────────────────────────────────────────────────────────────

    /** `want_content` — declare that a work should exist under a named profile. */
    fun wantWork(
        workId: String,
        qualityProfile: String,
        monitor: Boolean = true,
        reason: String? = null,
    ): McpResult<WantCreated> = mcp.call(
        "want_content",
        linkedMapOf(
            "work_id" to workId,
            "quality_profile" to qualityProfile,
            "monitor" to monitor,
            "reason" to reason,
        ),
    ).map { WantCreatedJson.parse(it) }

    /** `want_content` by title — for content the library has never seen. */
    fun wantTitle(
        title: String,
        type: MediaType,
        qualityProfile: String,
        year: Int? = null,
        monitor: Boolean = true,
        reason: String? = null,
    ): McpResult<WantCreated> = mcp.call(
        "want_content",
        linkedMapOf(
            "title" to title,
            "content_type" to type.apiName,
            "quality_profile" to qualityProfile,
            "year" to year,
            "monitor" to monitor,
            "reason" to reason,
        ),
    ).map { WantCreatedJson.parse(it) }

    /** `monitor_content` — keep looking for something better (true) or stop once satisfied (false). */
    fun monitor(desiredItemId: String, monitor: Boolean): McpResult<Unit> =
        mcp.call("monitor_content", mapOf("desired_item_id" to desiredItemId, "monitor" to monitor)).map { }

    /** `get_missing_content` — wants whose content is not satisfied. */
    fun missing(limit: Int = 200): List<Want> =
        WantJson.list(mcp.call("get_missing_content", mapOf("limit" to limit)).require())

    /** `get_upgrade_candidates` — satisfied, monitored wants that could still improve. */
    fun upgradeCandidates(limit: Int = 200): List<Want> =
        WantJson.list(mcp.call("get_upgrade_candidates", mapOf("limit" to limit)).require())

    /** `get_content_satisfaction` — why one want is (not) satisfied, rule by rule. */
    fun satisfaction(desiredItemId: String): McpResult<Satisfaction?> =
        mcp.call("get_content_satisfaction", mapOf("desired_item_id" to desiredItemId)).map {
            SatisfactionJson.parse(it)
        }

    /** `explain_release` — score releases against a profile without acquiring anything. */
    fun explain(qualityProfile: String, releases: List<ReleaseToExplain>): McpResult<Explanation?> = mcp.call(
        "explain_release",
        mapOf(
            "quality_profile" to qualityProfile,
            "releases" to releases.map { it.toArguments() },
        ),
    )
        .map { ExplanationJson.parse(it) }

    /** `search_releases` — queue an indexer search for a want now; the answer is a job, not releases. */
    fun searchReleases(desiredItemId: String): McpResult<QueuedJob> =
        mcp.call("search_releases", mapOf("desired_item_id" to desiredItemId)).map { QueuedJobJson.parse(it) }

    /** `acquire_release` — fetch one named candidate. A profile-rejected candidate is refused by rule; the refusal is returned to show. */
    fun acquire(desiredItemId: String, candidateId: String): McpResult<QueuedJob> =
        mcp.call("acquire_release", mapOf("desired_item_id" to desiredItemId, "candidate_id" to candidateId)).map {
            QueuedJobJson.parse(it)
        }

    /** `verify_blob` — queue a re-hash of a blob's bytes. */
    fun verifyBlob(blobHash: String): McpResult<QueuedJob> =
        mcp.call("verify_blob", mapOf("blob_hash" to blobHash)).map { QueuedJobJson.parse(it) }

    /** `get_replica_status` — which peers hold a blob and whether each copy is verified. */
    fun replicas(blobHash: String): McpResult<List<Replica>> =
        mcp.call("get_replica_status", mapOf("blob_hash" to blobHash)).map { ReplicaJson.list(it) }

    /** `get_external_ids` — tmdb/imdb ids for a work. */
    fun externalIds(workId: String): List<ExternalId> =
        ExternalIdJson.list(mcp.call("get_external_ids", mapOf("work_id" to workId)).require())

    // ── playback ─────────────────────────────────────────────────────────────────

    /**
     * Scan the local network for renderers via SSDP, report discovered devices to the server,
     * then fetch the canonical renderer list from REST.
     *
     * The SSDP scan is performed by [JvmSsdpScanner]; each discovered LOCATION is POSTed to
     * `/api/v1/renderers/reported` in parallel (short timeout, failures logged not surfaced).
     * The server uses the client's source IP to infer its site and sorts that site's renderers
     * first. Returns a [RendererListResult] with the inferred [clientSite] and the full list.
     *
     * Falls back to MCP `list_renderers` only when the REST endpoint is unreachable.
     */
    fun renderers(refresh: Boolean = false, scanner: SsdpScanner = JvmSsdpScanner()): RendererListResult {
        // 1. SSDP scan — never throws; empty list on network error.
        val discovered = scanner.scan()

        // 2. Report each LOCATION to the server in parallel (best-effort, failures logged). The
        //    server fetches each description before answering, so serially this would cost one
        //    device-timeout per switched-off TV.
        discovered.parallelStream().forEach { ssdp ->
            val locBody = one.rarebit.heyarr.core.mcp.JsonWrite.obj(mapOf("location" to ssdp.location))
            try {
                val url = "$baseUrl/api/v1/renderers/reported"
                val resp = http.post(url, locBody, "application/json", credential.asHeader())
                if (resp.status !in HTTP_OK_MIN..HTTP_OK_MAX) {
                    System.err.println("renderers/reported: ${ssdp.location}: HTTP ${resp.status}")
                }
            } catch (e: IOException) {
                System.err.println("renderers/reported: ${ssdp.location}: ${e.message}")
            }
        }

        // 3. Fetch the authoritative list via REST.
        return try {
            val query = if (refresh) "?refresh=true" else ""
            val resp = http.get("$baseUrl/api/v1/renderers$query", credential.asHeader())
            if (resp.status in HTTP_OK_MIN..HTTP_OK_MAX) {
                RendererJson.listWithMeta(resp.body)
            } else {
                // Unexpected HTTP error — fall back to MCP list so the UI still populates.
                System.err.println("GET /api/v1/renderers returned ${resp.status}; falling back to MCP")
                val mcpList = RendererJson.list(
                    mcp.call("list_renderers", if (refresh) mapOf("refresh" to true) else emptyMap()).require(),
                )
                RendererListResult(clientSite = null, renderers = mcpList)
            }
        } catch (e: IOException) {
            System.err.println("GET /api/v1/renderers unreachable: ${e.message}; falling back to MCP")
            val mcpList = RendererJson.list(
                mcp.call("list_renderers", if (refresh) mapOf("refresh" to true) else emptyMap()).require(),
            )
            RendererListResult(clientSite = null, renderers = mcpList)
        }
    }

    /**
     * Play an asset on a renderer via REST `POST /renderers/{udn}/play`.
     *
     * Returns [CastResult.Success] on 200 with the `client_must_stay_connected` flag so the
     * UI can tell the user whether it is safe to close the window.
     * Returns [CastResult.Conflict] on 409 with a human-readable message and alternatives list.
     * Falls back to MCP `play_here` only when the REST endpoint itself is unreachable
     * (IOException), which should be rare once the server is reachable enough to list renderers.
     */
    fun playHere(assetId: String, renderer: String, udn: String? = null, forceDirect: Boolean = false): CastResult {
        if (udn != null) {
            val body = one.rarebit.heyarr.core.mcp.JsonWrite.obj(
                buildMap<String, Any?> {
                    put("asset_id", assetId)
                    if (forceDirect) put("force_direct", true)
                },
            )
            val resp = try {
                http.post("$baseUrl/api/v1/renderers/${enc(udn)}/play", body, "application/json", credential.asHeader())
            } catch (e: IOException) {
                System.err.println("renderers/play REST unreachable: ${e.message}")
                null
            }
            if (resp != null) return parsePlayResponse(resp.body, resp.status)
        }

        // MCP fallback: used when no UDN is known or REST is unreachable.
        val toolArgs = buildMap<String, Any?> {
            put("asset_id", assetId)
            put("renderer", renderer)
            if (forceDirect) put("force_direct", true)
        }
        return when (val viaTool = mcp.call("play_here", toolArgs).map { }) {
            is McpResult.Ok -> CastResult.Success(clientMustStayConnected = false)

            is McpResult.Refused -> CastResult.Refused(
                message = viaTool.message,
                tool = viaTool.tool,
                code = viaTool.code,
            )
        }
    }

    /** `control_playback` — pause / resume / stop. */
    fun control(renderer: String, action: String): McpResult<Unit> =
        mcp.call("control_playback", mapOf("renderer" to renderer, "action" to action)).map { }

    /** `playback_status` — what a renderer is doing and how far in. */
    fun playbackStatus(renderer: String): McpResult<PlaybackStatus?> =
        mcp.call("playback_status", mapOf("renderer" to renderer)).map { PlaybackStatusJson.parse(it) }

    /**
     * The URL to play [assetId] from, resolved through `POST /api/v1/playback/plan`
     * (§68, ADR-0069). The server compares what this client declares it can decode
     * against what the asset actually is and answers `direct` (play the blob as it
     * is) or `stream` (a `/playback/stream/{token}` URL whose bytes it re-encodes to
     * fragmented MP4). We declare a deliberately conservative profile — H.264 up to
     * [maxHeight], stereo AAC in MP4 — so that heavy 4K/HEVC is transcoded down to
     * something any player decodes smoothly (this machine has no hardware decoder),
     * while content already within the profile plays direct with no server work.
     *
     * Never throws: on any failure (no plan route, malformed answer, the asset not
     * found) it falls back to the direct blob URL, so playback never depends on the
     * plan succeeding. Returns an absolute URL; the plan's own URL is relative.
     */
    fun playbackTarget(assetId: String, blobHash: String, maxHeight: Int = DEFAULT_MAX_HEIGHT): PlaybackTarget {
        val fallback = PlaybackTarget(blobUrl(baseUrl, blobHash))
        if (assetId.isBlank()) return fallback
        val body = JsonWrite.obj(
            mapOf(
                "asset_id" to assetId,
                "client" to mapOf(
                    "containers" to listOf("mp4"),
                    "video" to listOf("h264"),
                    "audio" to listOf("aac"),
                    "audio_languages" to listOf("en"),
                    "max_height" to maxHeight,
                ),
            ),
        )
        return try {
            val resp = http.post("$baseUrl/api/v1/playback/plan", body, "application/json", credential.asHeader())
            if (resp.status != 200) return fallback
            val root = JsonScan.rootObject(resp.body) ?: return fallback
            val url = JsonScan.stringField(root, "url")?.takeIf { it.isNotBlank() } ?: return fallback
            val abs = if (url.startsWith("http")) url else baseUrl.trimEnd('/') + url
            // The plan's source carries the true runtime; use it as the scrubber total
            // for a transcode stream (whose own duration only grows as it encodes).
            val dur = JsonScan.objectAt(root, "source")?.let {
                JsonScan.longField(it, "duration_seconds")
            }?.toDouble()?.takeIf {
                it >
                    0
            }
            // Only a `stream` is restart-seekable, and only off a URL that carries no
            // `start` of its own — so a second seek never stacks `?start=` on a first.
            val streamBase = abs.takeIf { JsonScan.stringField(root, "mode") == "stream" }
            PlaybackTarget(abs, dur, streamBase)
        } catch (_: Exception) {
            fallback
        }
    }

    // ── following & peers ────────────────────────────────────────────────────────

    /** `list_followed` — every standing subscription and whether its feed is healthy. */
    fun followed(limit: Int = 200): List<FollowedSource> =
        FollowedSourcesJson.parse(mcp.call("list_followed", mapOf("limit" to limit)).require())

    /** `follow_source` — subscribe to a URL or TVDB id under a profile. */
    fun follow(
        url: String?,
        tvdbId: String?,
        title: String?,
        qualityProfile: String,
        backfill: String = "from_now",
        type: String? = null,
        reason: String? = null,
    ): McpResult<FollowedSource?> = mcp.call(
        "follow_source",
        linkedMapOf(
            "url" to url?.ifBlank { null },
            "tvdb_id" to tvdbId?.ifBlank { null },
            "title" to title?.ifBlank { null },
            "quality_profile" to qualityProfile,
            "backfill" to backfill,
            "type" to type,
            "reason" to reason,
        ),
    ).map { FollowedSourcesJson.parseOne(it) }

    /** `unfollow` — stop polling; the archive is always kept in phase 1. */
    fun unfollow(sourceId: String): McpResult<Unit> =
        mcp.call("unfollow", mapOf("source_id" to sourceId, "keep_archive" to true)).map { }

    /** `get_peer_status` — the peers this node knows about. */
    fun peers(): PeerStatus = PeerJson.parse(mcp.call("get_peer_status", emptyMap()).require())

    /** `sync_peer` — reconcile against one peer now. */
    fun syncPeer(peer: String): McpResult<QueuedJob> =
        mcp.call("sync_peer", mapOf("peer" to peer)).map { QueuedJobJson.parse(it) }

    // ── REST reads with no tool equivalent ───────────────────────────────────────

    /** `GET /api/v1/works` — the whole library, recent first (existing client). */
    fun works(): List<Work> = library.listWorks()

    /** `GET /api/v1/works/{id}` — one work with its artwork + primary asset (existing client). */
    fun work(id: String): WorkDetail? = detail.getWorkDetail(id)

    /** `GET /api/v1/works/{id}/assets` — a work's files (the music client's reader, reused). */
    fun assets(workId: String): List<Track> = pageAll(
        url = { c -> paged("$baseUrl/api/v1/works/${enc(workId)}/assets?limit=200", c) },
        parse = TracksJson::parse,
        cursor = TracksJson::nextCursor,
        what = "GET /works/{id}/assets",
    )

    /**
     * `POST /api/v1/desired` at **edition** scope — a want for ONE season (heyarr-core
     * models the edition of an episodic work as its season, ADR-0056). There is no MCP
     * tool for a scoped want; this REST route is the one the mobile client uses.
     */
    fun wantEdition(
        workId: String,
        editionId: String,
        qualityProfile: String,
        monitor: Boolean = true,
        reason: String? = null,
    ): McpResult<DesiredItem?> {
        val body = one.rarebit.heyarr.core.mcp.JsonWrite.obj(
            linkedMapOf(
                "scope" to "edition",
                "work_id" to workId,
                "edition_id" to editionId,
                "quality_profile" to qualityProfile,
                "monitor" to monitor,
                "reason" to reason,
            ),
        )
        val resp = try {
            http.post("$baseUrl/api/v1/desired", body, "application/json", credential.asHeader())
        } catch (e: IOException) {
            throw McpTransportException("heyarr is unreachable: ${e.message}", e)
        }
        return when (resp.status) {
            200, 201 -> McpResult.Ok(DesiredItemJson.parseOne(resp.body))

            401, 403 -> throw McpTransportException(
                "heyarr refused the credential (HTTP ${resp.status})",
                null,
                resp.status,
            )

            else -> McpResult.Refused(
                Problem.message(resp.body, resp.status, "want season"),
                "POST /desired",
                resp.status,
            )
        }
    }

    private val sidecars = SidecarClient(http, baseUrl, credential)

    /**
     * One of the node's work-scoped caption or cover levers ([SidecarAction]). The node answers
     * with its own counts; a refusal (no provider configured, a scope it will not take) comes back
     * with the node's wording.
     */
    fun sidecar(action: SidecarAction, workId: String, language: String? = null): SidecarReply =
        sidecars.request(action, workId, language)

    // ── telemetry (the connection sheet) ─────────────────────────────────────────
    fun sessionInfo(): SessionInfo? = SessionInfoJson.parse(get("$baseUrl/api/v1/session", "GET /session"))
    fun providers(): List<ProviderInfo> = ProviderJson.list(get("$baseUrl/api/v1/providers", "GET /providers"))
    fun capabilities(): Capabilities = CapabilitiesJson.parse(get("$baseUrl/api/v1/capabilities", "GET /capabilities"))
    fun libraries(): List<LibraryInfo> = LibraryInfoJson.list(get("$baseUrl/api/v1/libraries", "GET /libraries"))
    fun jobs(limit: Int = 8): List<JobInfo> = JobJson.list(get("$baseUrl/api/v1/jobs?limit=$limit", "GET /jobs"))

    /** `GET /api/v1/consumption/continue` — the node's continue rail: the newest unfinished playback session per work (ADR-0075). */
    fun continueRail(limit: Int = 20): List<ContinueEntry> =
        ContinueJson.list(get("$baseUrl/api/v1/consumption/continue?limit=$limit", "GET /consumption/continue"))

    /** `GET /api/v1/followed-sources/{id}/items` — a followed source's archived items (existing feeds client). */
    fun followedItems(sourceId: String): List<one.rarebit.heyarr.core.feeds.FollowedItem> =
        one.rarebit.heyarr.desktop.feeds.FeedsClient(http, baseUrl, credential).listItems(sourceId)

    /** `GET /api/v1/quality-profiles` — the names a want must be measured against. */
    fun qualityProfiles(): List<QualityProfile> =
        QualityProfileJson.list(get("$baseUrl/api/v1/quality-profiles", "GET /quality-profiles"))

    /** `GET /api/v1/desired` — every want with its acquisition state (the library-status index). */
    fun desired(): List<DesiredItem> = pageAll(
        url = { c -> paged("$baseUrl/api/v1/desired?limit=200", c) },
        parse = DesiredItemJson::list,
        cursor = DesiredItemJson::nextCursor,
        what = "GET /desired",
    )

    /** `GET /api/v1/desired/{id}/candidates` — the releases the last search found, scored. */
    fun candidates(desiredItemId: String): CandidateList? = CandidateJson.parse(
        get("$baseUrl/api/v1/desired/${enc(desiredItemId)}/candidates", "GET /desired/{id}/candidates"),
    )

    /** A cheap liveness probe for the offline banner: one authenticated page of one work. The HTTP status; a transport failure throws. */
    fun ping(): Int = http.get("$baseUrl/api/v1/works?limit=1", credential.asHeader()).status

    /** Explicit catalogue removal; subscriptions must be stopped before the server accepts it. */
    fun removeWork(workId: String) {
        val subscriptions = followed().filter { it.workId == workId }
        for (source in subscriptions) {
            when (val result = unfollow(source.id)) {
                is McpResult.Refused -> throw IllegalStateException(result.message)
                is McpResult.Ok -> {}
            }
        }
        val response = http.delete("$baseUrl/api/v1/works/${enc(workId)}", credential.asHeader())
        if (response.status != 204) {
            throw IllegalStateException(
                (if (subscriptions.isNotEmpty()) "Following stopped, but removal failed: " else "") +
                    Problem.message(response.body, response.status, "Remove from library"),
            )
        }
    }

    // ── plumbing ─────────────────────────────────────────────────────────────────

    private fun get(url: String, what: String): String {
        val resp = try {
            http.get(url, credential.asHeader())
        } catch (e: IOException) {
            throw McpTransportException("heyarr is unreachable: ${e.message ?: e.javaClass.simpleName}", e)
        }
        if (resp.status == 401 ||
            resp.status == 403
        ) {
            throw McpTransportException("heyarr refused the credential (HTTP ${resp.status})", null, resp.status)
        }
        if (resp.status !=
            200
        ) {
            throw McpTransportException(Problem.message(resp.body, resp.status, what), null, resp.status)
        }
        return resp.body
    }

    private fun <T> pageAll(
        url: (String?) -> String,
        parse: (String) -> List<T>,
        cursor: (String) -> String?,
        what: String,
    ): List<T> {
        val all = ArrayList<T>()
        var next: String? = null
        var pages = 0
        do {
            val body = get(url(next), what)
            all.addAll(parse(body))
            next = cursor(body)
            pages++
        } while (next != null && pages < 50)
        return all
    }

    private fun paged(base: String, cursor: String?) =
        if (cursor.isNullOrBlank()) base else "$base&cursor=${enc(cursor)}"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /**
         * The tallest picture to play without transcoding down. This laptop has no
         * hardware decoder, so 4K software-decodes into a stutter; 1080p H.264 plays
         * smoothly, and the server transcodes anything taller to it on the fly.
         */
        const val DEFAULT_MAX_HEIGHT = 1080

        /** The artwork/asset stream URL — the hash goes in verbatim (see `BlobStream`). */
        fun blobUrl(baseUrl: String, blobHash: String) = baseUrl.trimEnd('/') + "/api/v1/blobs/" + blobHash + "/content"
        fun blobUrlFromPath(baseUrl: String, contentPath: String) = baseUrl.trimEnd('/') + contentPath
    }
}

/**
 * A 409 from `POST /renderers/{udn}/play`. A route refusal (ADR-0111) carries a `route-…` code
 * and its alternatives, and its `detail` is the planner's reason. Any other 409 — the codec plan
 * the device can't take — stays a plain refusal, so the picker still offers "Cast anyway".
 */
private fun parsePlayConflict(body: String): CastResult {
    val code = JsonScan.stringField(body, "code")
    if (code == null || !code.startsWith("route-")) {
        val message = Problem.message(body, HTTP_CONFLICT, "play on renderer")
        return CastResult.Refused(message, "renderers/play", HTTP_CONFLICT)
    }
    val reasonHuman = JsonScan.stringField(body, "detail") ?: "The server refused this route."
    val alternatives = JsonScan.objectsOf(body, listOf("alternatives")).mapNotNull { alt ->
        val kind = JsonScan.stringField(alt, "kind") ?: return@mapNotNull null
        PlayAlternative(
            kind = kind,
            assetId = JsonScan.stringField(alt, "asset_id"),
            heightPx = JsonScan.intField(alt, "height_px"),
            bitrateKbps = JsonScan.intField(alt, "bitrate_kbps"),
            site = JsonScan.stringField(alt, "site"),
        )
    }
    return CastResult.Conflict(code = code, reasonHuman = reasonHuman, alternatives = alternatives)
}

internal fun parsePlayResponse(body: String, status: Int): CastResult = when {
    status in HTTP_OK_MIN..HTTP_OK_MAX ->
        CastResult.Success(clientMustStayConnected = JsonScan.boolField(body, "client_must_stay_connected") ?: false)

    status == HTTP_CONFLICT -> parsePlayConflict(body)

    else -> CastResult.Refused(
        message = Problem.message(body, status, "play on renderer"),
        tool = "renderers/play",
        code = status,
    )
}

/**
 * A tool's answer, typed: the value, or the refusal to show verbatim. Screens `when` on
 * it; the rule-code text in [Refused] is the server's own wording.
 */
sealed interface McpResult<out T> {
    data class Ok<T>(val value: T) : McpResult<T>
    data class Refused(val message: String, val tool: String, val code: Int) : McpResult<Nothing>

    fun getOrNull(): T? = (this as? Ok<T>)?.value
}

private inline fun <T> McpOutcome.map(read: (String) -> T): McpResult<T> = when (this) {
    is McpOutcome.Ok -> McpResult.Ok(read(json))
    is McpOutcome.Refused -> McpResult.Refused(error.message, error.tool, error.code)
}

/**
 * An alternative the server offers when play is refused with a 409 Conflict.
 *
 * [kind]         one of `"smaller_copy"` or `"copy_to_site_first"`.
 * [assetId]      the asset to play instead (present for `smaller_copy`).
 * [heightPx]     vertical resolution of the smaller copy, e.g. 1080.
 * [bitrateKbps]  approximate bitrate of the smaller copy.
 * [site]         the site the content needs to be copied to first (present for `copy_to_site_first`).
 */
data class PlayAlternative(
    val kind: String,
    val assetId: String?,
    val heightPx: Int?,
    val bitrateKbps: Int?,
    val site: String?,
)

/**
 * The outcome of a [HeyarrApi.playHere] call.
 *
 * [Success]  Play started. [clientMustStayConnected] is true when the app is acting as a relay
 *            and must be kept alive; false when the stream is self-contained on the renderer.
 * [Refused]  The renderer or server rejected the request outright (non-200, non-409 status, or MCP refusal).
 * [Conflict] The route planner refused (409 with a `route-…` [code]). [reasonHuman] is the
 *            server's explanation; [alternatives] lists what the UI may offer instead.
 */
sealed interface CastResult {
    data class Success(val clientMustStayConnected: Boolean) : CastResult
    data class Refused(val message: String, val tool: String, val code: Int) : CastResult
    data class Conflict(val code: String, val reasonHuman: String, val alternatives: List<PlayAlternative>) : CastResult
}
