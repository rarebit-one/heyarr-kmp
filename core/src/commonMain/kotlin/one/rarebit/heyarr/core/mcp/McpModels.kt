package one.rarebit.heyarr.core.mcp

import one.rarebit.heyarr.core.net.JsonScan

/**
 * One line of a quality-profile verdict — heyarr-core's `Reason` — as every scoring
 * tool returns it (`explain_release`, `get_content_satisfaction`, the candidates list).
 *
 * [rule] is the STABLE rule code (`resolution.gte`, `source.nin`, `size_bytes.lte` …)
 * and the thing a person can act on, so the UI renders it verbatim, never reworded.
 * [section] is which part of the profile it belongs to (`accept` / `prefer` /
 * `terminal`); [result] is what happened: `pass`, `fail`, `bonus`, `miss`, or
 * `undetermined` — the last meaning "the provider could not tell", which is a different
 * answer from "wrong" and sends a person somewhere different.
 */
data class Reason(
    val rule: String,
    val section: String,
    val result: String,
    val detail: String,
    val score: Int? = null,
) {
    val isFailure: Boolean get() = result == "fail"
    val isUndetermined: Boolean get() = result == "undetermined"
}

object ReasonJson {
    /** All reasons in the array [key] of one object slice (empty when absent). */
    fun list(obj: String, key: String = "reasons"): List<Reason> =
        JsonScan.objectsOf(obj, listOf(key)).mapNotNull { parse(it) }

    fun parse(obj: String): Reason? {
        val rule = JsonScan.stringField(obj, "rule") ?: return null
        return Reason(
            rule = rule,
            section = JsonScan.stringField(obj, "section") ?: "",
            result = JsonScan.stringField(obj, "result") ?: "",
            detail = JsonScan.stringField(obj, "detail") ?: "",
            score = JsonScan.intField(obj, "score"),
        )
    }
}

/**
 * A want as `get_missing_content` / `get_upgrade_candidates` list it: the desire that
 * something SHOULD exist, measured against a named profile. [state] is heyarr's
 * acquisition state word (`MISSING`, `SELECTED`, `AVAILABLE`, `FULLY_SATISFIED` …),
 * kept as sent.
 */
data class Want(
    val desiredItemId: String,
    val workId: String?,
    val title: String,
    val qualityProfile: String?,
    val state: String,
    val monitor: Boolean,
    val reason: String?,
)

object WantJson {
    fun list(body: String): List<Want> =
        JsonScan.objectsOf(body, listOf("wants", "items")).mapNotNull { parse(it) }

    fun parse(obj: String): Want? {
        val id = JsonScan.firstString(obj, listOf("desired_item_id", "id")) ?: return null
        return Want(
            desiredItemId = id,
            workId = JsonScan.stringField(obj, "work_id"),
            title = JsonScan.stringField(obj, "title") ?: id,
            qualityProfile = JsonScan.stringField(obj, "quality_profile"),
            state = JsonScan.stringField(obj, "state") ?: "UNKNOWN",
            monitor = JsonScan.boolField(obj, "monitor") ?: true,
            reason = JsonScan.stringField(obj, "reason"),
        )
    }

    /** `{count, truncated}` of a list body — true when the node cut the list at its maximum. */
    fun truncated(body: String): Boolean =
        JsonScan.rootObject(body)?.let { JsonScan.boolField(it, "truncated") } ?: false
}

/** One held asset judged against the profile, inside [Satisfaction]. */
data class AssetVerdict(
    val assetId: String,
    val accepted: Boolean,
    val score: Int,
    val terminal: Boolean,
    val reasons: List<Reason>,
) {
    /** The reasons that actually rejected it (accept-section failures). */
    val rejectedBy: List<Reason> get() = reasons.filter { it.section == "accept" && it.isFailure }
}

/**
 * `get_content_satisfaction` — the whole answer to "I have this, why does heyarr say it
 * is missing": content (what is held and which rule rejected each asset), placement
 * (whether the bytes are on every peer that should hold them — `unproven` on a
 * single-node fabric) and upgrade eligibility.
 */
data class Satisfaction(
    val desiredItemId: String,
    val state: String,
    val contentSatisfaction: String,
    val assets: List<AssetVerdict>,
    val placementSatisfaction: String,
    val placementDetail: String,
    val placementUnproven: Boolean,
    val upgradeEligible: Boolean,
    val upgradeStatus: String,
    val upgradeDetail: String,
)

object SatisfactionJson {
    fun parse(body: String): Satisfaction? {
        val root = JsonScan.rootObject(body) ?: return null
        val id = JsonScan.stringField(root, "desired_item_id") ?: return null
        val content = JsonScan.objectAt(root, "content")
        val placement = JsonScan.objectAt(root, "placement")
        val upgrade = JsonScan.objectAt(root, "upgrade")
        val assets = content?.let { c ->
            JsonScan.objectsOf(c, listOf("assets")).mapNotNull { a ->
                val assetId = JsonScan.stringField(a, "asset_id") ?: return@mapNotNull null
                AssetVerdict(
                    assetId = assetId,
                    accepted = JsonScan.boolField(a, "accepted") ?: false,
                    score = JsonScan.intField(a, "score") ?: 0,
                    terminal = JsonScan.boolField(a, "terminal") ?: false,
                    reasons = ReasonJson.list(a),
                )
            }
        } ?: emptyList()
        return Satisfaction(
            desiredItemId = id,
            state = JsonScan.stringField(root, "state") ?: "UNKNOWN",
            contentSatisfaction = content?.let { JsonScan.stringField(it, "satisfaction") } ?: "unknown",
            assets = assets,
            placementSatisfaction = placement?.let { JsonScan.stringField(it, "satisfaction") } ?: "unknown",
            placementDetail = placement?.let { JsonScan.stringField(it, "detail") } ?: "",
            placementUnproven = placement?.let { JsonScan.boolField(it, "unproven") } ?: false,
            upgradeEligible = upgrade?.let { JsonScan.boolField(it, "eligible") } ?: false,
            upgradeStatus = upgrade?.let { JsonScan.stringField(it, "status") } ?: "unknown",
            upgradeDetail = upgrade?.let { JsonScan.stringField(it, "detail") } ?: "",
        )
    }
}

/** One release as `explain_release` ranked it. */
data class RankedRelease(
    val id: String,
    val title: String,
    val accepted: Boolean,
    val score: Int,
    val terminal: Boolean,
    val reasons: List<Reason>,
    val rejectedBy: List<Reason>,
)

/** The `explain_release` answer: which release the scorer would pick, and every rule it weighed. */
data class Explanation(
    val qualityProfile: String,
    val selected: String?,
    val ranked: List<RankedRelease>,
)

object ExplanationJson {
    fun parse(body: String): Explanation? {
        val root = JsonScan.rootObject(body) ?: return null
        val ranked = JsonScan.objectsOf(root, listOf("ranked")).mapNotNull { r ->
            val id = JsonScan.stringField(r, "id") ?: return@mapNotNull null
            RankedRelease(
                id = id,
                title = JsonScan.stringField(r, "title") ?: id,
                accepted = JsonScan.boolField(r, "accepted") ?: false,
                score = JsonScan.intField(r, "score") ?: 0,
                terminal = JsonScan.boolField(r, "terminal") ?: false,
                reasons = ReasonJson.list(r, "reasons"),
                rejectedBy = ReasonJson.list(r, "rejected_by"),
            )
        }
        return Explanation(
            qualityProfile = JsonScan.stringField(root, "quality_profile") ?: "",
            selected = JsonScan.stringField(root, "selected"),
            ranked = ranked,
        )
    }
}

/**
 * The attributes a release is described by when asking `explain_release`. Every field
 * is optional and an unset one is LEFT OUT of the request (see [JsonWrite]) so the
 * scorer reports it as undetermined rather than wrong.
 */
data class ReleaseAttributes(
    val resolution: Int? = null,
    val source: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val hdr: Boolean? = null,
    val language: String? = null,
    val sizeBytes: Long? = null,
) {
    fun toArguments(): Map<String, Any?> = linkedMapOf(
        "resolution" to resolution,
        "source" to source?.takeIf { it.isNotBlank() },
        "video_codec" to videoCodec?.takeIf { it.isNotBlank() },
        "audio_codec" to audioCodec?.takeIf { it.isNotBlank() },
        "audio_channels" to audioChannels,
        "hdr" to hdr,
        "language" to language?.takeIf { it.isNotBlank() },
        "size_bytes" to sizeBytes,
    )
}

/** A release to explain: your own [id] (breaks ties), its [title], and what you know about it. */
data class ReleaseToExplain(val id: String, val title: String, val attributes: ReleaseAttributes) {
    fun toArguments(): Map<String, Any?> = linkedMapOf("id" to id, "title" to title, "attributes" to attributes.toArguments())
}

/** A network renderer (`list_renderers`): a television, speaker or projector heyarr can play to. */
data class Renderer(
    val udn: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
) {
    val subtitle: String get() = listOfNotNull(manufacturer, model).joinToString(" · ")
}

object RendererJson {
    fun list(body: String): List<Renderer> =
        JsonScan.objectsOf(body, listOf("renderers", "items")).mapNotNull { r ->
            val name = JsonScan.stringField(r, "name") ?: return@mapNotNull null
            Renderer(
                udn = JsonScan.stringField(r, "udn") ?: name,
                name = name,
                manufacturer = JsonScan.stringField(r, "manufacturer"),
                model = JsonScan.stringField(r, "model"),
            )
        }
}

/**
 * `playback_status` for one renderer. [state] is the device's transport word
 * (`PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`, `NO_MEDIA_PRESENT` …); [elapsedSeconds]
 * may legitimately be 0 on a device that never reports a position.
 */
data class PlaybackStatus(
    val renderer: String,
    val state: String,
    val playing: Boolean,
    val elapsedSeconds: Long,
    val durationSeconds: Long? = null,
    val title: String? = null,
)

object PlaybackStatusJson {
    fun parse(body: String): PlaybackStatus? {
        val root = JsonScan.rootObject(body) ?: return null
        return PlaybackStatus(
            renderer = JsonScan.stringField(root, "renderer") ?: "",
            state = JsonScan.stringField(root, "state") ?: "UNKNOWN",
            playing = JsonScan.boolField(root, "playing") ?: false,
            elapsedSeconds = JsonScan.longField(root, "elapsed_seconds") ?: 0L,
            durationSeconds = JsonScan.longField(root, "duration_seconds"),
            title = JsonScan.firstString(root, listOf("title", "asset_title")),
        )
    }
}

/** A peer of this node (`get_peer_status`). */
data class Peer(
    val peerId: String,
    val name: String,
    val site: String?,
    val mode: String?,
    val isSelf: Boolean,
)

data class PeerStatus(val peers: List<Peer>, val note: String?)

object PeerJson {
    fun parse(body: String): PeerStatus {
        val peers = JsonScan.objectsOf(body, listOf("peers", "items")).mapNotNull { p ->
            val id = JsonScan.stringField(p, "peer_id") ?: return@mapNotNull null
            Peer(
                peerId = id,
                name = JsonScan.stringField(p, "name") ?: id,
                site = JsonScan.stringField(p, "site"),
                mode = JsonScan.stringField(p, "mode"),
                isSelf = JsonScan.boolField(p, "is_self") ?: false,
            )
        }
        return PeerStatus(peers, JsonScan.rootObject(body)?.let { JsonScan.stringField(it, "note") })
    }
}

/** Where a blob's copies are (`get_replica_status`): a pending or corrupt copy is NOT a copy. */
data class Replica(val peer: String, val state: String, val verified: Boolean)

object ReplicaJson {
    fun list(body: String): List<Replica> =
        JsonScan.objectsOf(body, listOf("replicas", "peers", "items")).mapNotNull { r ->
            val peer = JsonScan.firstString(r, listOf("peer", "peer_name", "name", "peer_id")) ?: return@mapNotNull null
            Replica(
                peer = peer,
                state = JsonScan.firstString(r, listOf("state", "status")) ?: "unknown",
                verified = JsonScan.boolField(r, "verified") ?: false,
            )
        }
}

/**
 * A work as `search_content` returns it — a library work matched by title, with its
 * artwork. Distinct from the REST `Work` because the key is `work_id`, the year is
 * optional and the artwork embed is inlined.
 */
data class SearchHit(
    val workId: String,
    val contentType: String?,
    val title: String,
    val year: Int?,
    val artworkPath: String?,
    val artworkHash: String?,
    val tvdbId: String?,
    val attributes: Map<String, String>,
) {
    val creator: String? get() = attributes["artist"] ?: attributes["author"] ?: attributes["host"] ?: attributes["narrator"]
}

/** An episode/part hit (`episodes`) — a scanned episode with its file, or a followed source's projected item. */
data class EpisodeHit(
    val id: String,
    val kind: String,
    val title: String,
    val workId: String?,
    val workTitle: String?,
    val contentType: String?,
    val assetId: String?,
    val blobHash: String?,
    val mime: String? = null,
)

data class SearchHits(val works: List<SearchHit>, val episodes: List<EpisodeHit>, val truncated: Boolean)

object SearchHitsJson {
    fun parse(body: String): SearchHits {
        val works = JsonScan.objectsOf(body, listOf("works")).mapNotNull { w ->
            val id = JsonScan.firstString(w, listOf("work_id", "id")) ?: return@mapNotNull null
            val artwork = JsonScan.objectAt(w, "artwork")
            SearchHit(
                workId = id,
                contentType = JsonScan.firstString(w, listOf("content_type", "type")),
                title = JsonScan.firstString(w, listOf("title", "name", "sort_title")) ?: id,
                year = JsonScan.intField(w, "year"),
                artworkPath = artwork?.let { JsonScan.stringField(it, "content_url") },
                artworkHash = artwork?.let { JsonScan.stringField(it, "blob_hash") },
                tvdbId = JsonScan.stringField(w, "tvdb_id"),
                attributes = JsonScan.objectAt(w, "attributes")?.let { stringAttributes(it) } ?: emptyMap(),
            )
        }
        val episodes = JsonScan.objectsOf(body, listOf("episodes")).mapNotNull { e ->
            val id = JsonScan.firstString(e, listOf("id", "edition_id", "item_id")) ?: return@mapNotNull null
            val asset = JsonScan.objectAt(e, "primary_asset")
            EpisodeHit(
                id = id,
                kind = JsonScan.stringField(e, "kind") ?: "edition",
                title = JsonScan.firstString(e, listOf("title", "label", "item_key")) ?: id,
                workId = JsonScan.stringField(e, "work_id"),
                workTitle = JsonScan.stringField(e, "work_title"),
                contentType = JsonScan.stringField(e, "content_type"),
                assetId = asset?.let { JsonScan.stringField(it, "asset_id") },
                blobHash = asset?.let { JsonScan.stringField(it, "blob_hash") },
                mime = asset?.let { JsonScan.stringField(it, "mime") },
            )
        }
        val truncated = JsonScan.rootObject(body)?.let { JsonScan.boolField(it, "truncated") } ?: false
        return SearchHits(works, episodes, truncated)
    }

    /** The string-valued members of a flat attributes object (creator names, narrators …). */
    private fun stringAttributes(obj: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (key in ATTRIBUTE_KEYS) JsonScan.stringField(obj, key)?.let { out[key] = it }
        return out
    }

    private val ATTRIBUTE_KEYS = listOf("artist", "author", "album", "narrator", "host", "series", "genre", "runtime", "pages", "duration")
}

/** A followed source's identity for the UI — `list_followed` (the MCP twin of `/followed-sources`). */
data class WantCreated(val desiredItemId: String?, val workId: String?, val state: String?, val title: String?)

object WantCreatedJson {
    fun parse(body: String): WantCreated {
        val root = JsonScan.rootObject(body)
        return WantCreated(
            desiredItemId = root?.let { JsonScan.firstString(it, listOf("desired_item_id", "id")) },
            workId = root?.let { JsonScan.stringField(it, "work_id") },
            state = root?.let { JsonScan.stringField(it, "state") },
            title = root?.let { JsonScan.stringField(it, "title") },
        )
    }
}

/** A queued job (`search_releases`, `verify_blob`, `sync_peer` all answer this way): accepted, not done. */
data class QueuedJob(val jobId: String?, val message: String?)

object QueuedJobJson {
    fun parse(body: String): QueuedJob {
        val root = JsonScan.rootObject(body)
        val job = root?.let { JsonScan.objectAt(it, "job") }
        return QueuedJob(
            jobId = (job ?: root)?.let { JsonScan.firstString(it, listOf("job_id", "id")) },
            message = root?.let { JsonScan.firstString(it, listOf("message", "detail", "note", "state")) },
        )
    }
}

/**
 * A discovery hit (`discover_content`): content the library does NOT hold yet, from
 * any configured metadata provider (heyarr-core ADR-0099) — TVDB/TMDB series, TMDB
 * movies, Open Library books, MusicBrainz music.
 *
 * [type] says which door acts on it: the four followed-source kinds (`tv_series`,
 * `podcast`, `youtube_channel`, `rss_feed`) are followed — [tvdbId] is set and
 * [followable] is true; everything else (`movie`, `book`, `music`) has no calendar
 * and is wanted by title instead — [tvdbId] is null and [followable] is false.
 * [source]/[externalId] are the provider's own identity for the hit (e.g. "tmdb"/
 * "603", "openlibrary"/"OL893415W"), carried for display and future cross-reference
 * even when there is no follow door to act on it in one step. [posterUrl]/
 * [backdropUrl] are provider-hosted images — plain remote URLs to render (and may
 * cache) like any other image, never proxied through heyarr. Every provider that
 * has an artwork notion sets posterUrl (TMDB, Open Library's book cover,
 * MusicBrainz's Cover Art Archive release image); backdropUrl is TMDB-only, since
 * none of the others has a wider-scene-image concept.
 */
data class DiscoveryHit(
    val tvdbId: String?,
    val title: String,
    val year: Int?,
    val overview: String?,
    val type: String?,
    val source: String?,
    val externalId: String?,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
) {
    /** True for the four followed-source kinds; false for movie/book/music. */
    val followable: Boolean get() = type == "tv_series" || type == "podcast" || type == "youtube_channel" || type == "rss_feed"
}

object DiscoveryJson {
    fun list(body: String): List<DiscoveryHit> =
        JsonScan.objectsOf(body, listOf("results", "candidates", "series", "items")).mapNotNull { d ->
            val title = JsonScan.firstString(d, listOf("title", "name")) ?: return@mapNotNull null
            DiscoveryHit(
                tvdbId = JsonScan.stringField(d, "tvdb_id") ?: JsonScan.longField(d, "tvdb_id")?.toString(),
                title = title,
                year = JsonScan.intField(d, "year"),
                overview = JsonScan.firstString(d, listOf("overview", "description")),
                type = JsonScan.stringField(d, "type"),
                source = JsonScan.stringField(d, "source"),
                externalId = JsonScan.stringField(d, "external_id"),
                posterUrl = JsonScan.stringField(d, "poster_url"),
                backdropUrl = JsonScan.stringField(d, "backdrop_url"),
            )
        }
}

/** An external id pair (`get_external_ids`). */
data class ExternalId(val source: String, val value: String)

object ExternalIdJson {
    fun list(body: String): List<ExternalId> =
        JsonScan.objectsOf(body, listOf("external_ids", "items")).mapNotNull { e ->
            val source = JsonScan.firstString(e, listOf("source", "scheme")) ?: return@mapNotNull null
            val value = JsonScan.stringField(e, "value") ?: JsonScan.longField(e, "value")?.toString() ?: return@mapNotNull null
            ExternalId(source, value)
        }
}
