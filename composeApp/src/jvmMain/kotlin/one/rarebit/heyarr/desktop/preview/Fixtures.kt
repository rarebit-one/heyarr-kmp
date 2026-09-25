package one.rarebit.heyarr.desktop.preview

import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.core.net.JsonScan

/**
 * Canned heyarr answers — the shapes observed on a live node, trimmed — served by a
 * fake [HttpTransport] so screens and tests run with no network. MCP calls are routed
 * by tool name (read out of the JSON-RPC body); REST reads by path.
 */
object Fixtures {
    const val HASH = "blake3:98285d906a5d683b8d22ff0b2fec97d05e71c548be67ec826da88fe56a0a6bc0"
    const val YELLOWSTONE = "01a032b6-6594-782c-9952-52c8526dde13"
    const val SINTEL = "01a032c1-f553-727a-80a9-afe63acd37bd"
    const val SINTEL_WANT = "01a032c1-f554-7157-90c8-b3a9825dc435"

    private fun work(id: String, type: String, title: String, year: Int?, art: Boolean = false, attrs: String = "{}") =
        """{"id":"$id","work_id":"$id","content_type":"$type","title":"$title","sort_title":"${title.lowercase()}","year":${year ?: "null"},"attributes":$attrs,"created_at":"2026-08-24T07:40:09Z","updated_at":"2026-09-01T10:00:00Z","artwork":${if (art) """{"asset_id":"a-$id","blob_hash":"$HASH","mime":"image/jpeg","content_url":"/api/v1/blobs/$HASH/content"}""" else "null"}}"""

    val works = listOf(
        work(YELLOWSTONE, "series", "Yellowstone", 2018, art = true),
        work(SINTEL, "movie", "Sintel", 2010),
        work(
            "w-dune",
            "movie",
            "Dune: Part Two",
            2024,
            art = true,
            attrs = """{"runtime":"166 min","genre":"Sci-fi"}""",
        ),
        work("w-severance", "series", "Severance", 2022, art = true),
        work("w-piranesi", "book", "Piranesi", 2020, attrs = """{"author":"Susanna Clarke","pages":"245"}"""),
        work(
            "w-dune-book",
            "book",
            "Dune",
            1965,
            attrs = """{"author":"Frank Herbert","pages":"412","series":"Dune"}""",
        ),
        work("w-kid-a", "music", "Kid A", 2000, attrs = """{"artist":"Radiohead","album":"Kid A"}"""),
        work("w-blue", "music", "Blue", 1971, attrs = """{"artist":"Joni Mitchell"}"""),
        work(
            "w-project-hail",
            "book",
            "Project Hail Mary",
            2021,
            attrs = """{"author":"Andy Weir","narrator":"Ray Porter"}""",
        ),
        work("w-cloudflare", "document", "Cloudflare Blog", null),
        work("w-ys-s4", "series", "Yellowstone Season 4 Mp4", null),
    )

    fun worksList() = """{"items":[${works.joinToString(",")}],"next_cursor":null}"""

    fun searchContent(query: String?, type: String?): String {
        val q = query?.lowercase().orEmpty()
        val hits = works.filter { w ->
            val title = JsonScan.stringField(w, "title")!!.lowercase()
            val t = JsonScan.stringField(w, "content_type")
            (type == null || t == type) && (q.isEmpty() || title.contains(q)) && t != "document"
        }
        val episodes = if (q.isNotEmpty() && "yellowstone".contains(q) && type == null) {
            """{"id":"ep-1","kind":"edition","title":"S04E02 — Phantom Pain","work_id":"$YELLOWSTONE","work_title":"Yellowstone","content_type":"series","primary_asset":{"asset_id":"as-1","blob_hash":"$HASH"}}"""
        } else {
            ""
        }
        return """{"count":${hits.size},"truncated":false,"works":[${hits.joinToString(",")}],"episodes":[$episodes]}"""
    }

    val followed = """{"followed_sources":[
      {"id":"fs-1","work_id":"w-cloudflare","title":"Cloudflare Blog","type":"rss_feed","feed_ref":"https://blog.cloudflare.com/rss","quality_profile_id":"qp-everyday","monitor":true,"backfill":"from_now","items_known":12,"items_archived":12,"health":"ok"},
      {"id":"fs-2","work_id":"w-atp","title":"Accidental Tech Podcast","type":"podcast","feed_ref":"https://atp.fm/rss","quality_profile_id":"qp-everyday","monitor":true,"backfill":"from_now","items_known":640,"items_archived":3,"health":"ok"},
      {"id":"fs-3","work_id":"w-severance","title":"Severance","type":"tv_series","feed_ref":"tvdb:371980","quality_profile_id":"qp-living","monitor":true,"backfill":"full","items_known":19,"items_archived":19,"health":"ok"}
    ]}"""

    val missing = """{"count":3,"truncated":false,"wants":[
      {"desired_item_id":"$SINTEL_WANT","work_id":"$SINTEL","title":"Sintel","quality_profile":"living-room","state":"MISSING","monitor":true,"reason":"session validation: open-licence film, safe to name publicly"},
      {"desired_item_id":"d-piranesi","work_id":"w-piranesi","title":"Piranesi","quality_profile":"everyday","state":"SELECTED","monitor":true,"reason":"book club"},
      {"desired_item_id":"d-kid-a","work_id":"w-kid-a","title":"Kid A","quality_profile":"archival","state":"MISSING","monitor":true,"reason":null}
    ]}"""

    val upgrades = """{"count":1,"truncated":false,"wants":[
      {"desired_item_id":"d-yellowstone","work_id":"$YELLOWSTONE","title":"Yellowstone","quality_profile":"living-room","state":"FULLY_SATISFIED","monitor":true,"reason":"does a real 1080p-class master pass resolution>=1080"}
    ]}"""

    val desired = """{"items":[
      {"id":"$SINTEL_WANT","scope":"work","work_id":"$SINTEL","quality_profile_id":"qp-living","monitor":true,"reason":"session validation","acquisition":{"state":"MISSING","phase":"idle","managed":false,"content":"not_satisfied","placement":"unknown","detail":"1 of 2 indexer(s) answered with nothing; 1 could not be reached"}},
      {"id":"d-piranesi","scope":"work","work_id":"w-piranesi","quality_profile_id":"qp-everyday","monitor":true,"reason":"book club","acquisition":{"state":"SELECTED","phase":"fetching","managed":true,"content":"not_satisfied","placement":"unknown","detail":"1 candidate(s) selected"}},
      {"id":"d-kid-a","scope":"work","work_id":"w-kid-a","quality_profile_id":"qp-archival","monitor":true,"reason":null,"acquisition":{"state":"MISSING","phase":"idle","managed":false,"content":"not_satisfied","placement":"unknown","detail":"1 candidate(s), none acceptable"}},
      {"id":"d-yellowstone","scope":"work","work_id":"$YELLOWSTONE","quality_profile_id":"qp-living","monitor":true,"reason":"validate","acquisition":{"state":"FULLY_SATISFIED","phase":"idle","managed":true,"content":"satisfied","placement":"unknown","detail":""}},
      {"id":"d-dune","scope":"work","work_id":"w-dune","quality_profile_id":"qp-living","monitor":false,"reason":null,"acquisition":{"state":"AVAILABLE","phase":"idle","managed":true,"content":"satisfied","placement":"unknown","detail":""}}
    ],"next_cursor":null}"""

    val profiles = """{"items":[
      {"id":"qp-archival","name":"archival","description":"Keep the best there is. Never terminal: there is no condition under which this profile stops looking for something better."},
      {"id":"qp-everyday","name":"everyday","description":"A laptop or a tablet. Accepts 720p and up and is finished at 1080p — smaller files, reached sooner."},
      {"id":"qp-living","name":"living-room","description":"The big screen. Accepts 1080p and up, prefers HEVC/HDR/surround, finished at a 2160p remux."}
    ]}"""

    val reasonsHeld = """[
      {"rule":"resolution.gte","section":"accept","result":"pass","detail":"resolution 1080, which is at least 1080"},
      {"rule":"source.nin","section":"accept","result":"undetermined","detail":"the provider could not determine source, so this gate cannot be shown to hold"},
      {"rule":"video_codec.eq","section":"prefer","result":"miss","detail":"video_codec h264, which is not equal to hevc"},
      {"rule":"hdr.eq","section":"prefer","result":"miss","detail":"hdr false, which is not equal to true"},
      {"rule":"audio_channels.gte","section":"prefer","result":"miss","detail":"audio_channels 2, which is not at least 6"},
      {"rule":"resolution.gte","section":"terminal","result":"miss","detail":"resolution 1080, which is not at least 2160"},
      {"rule":"source.eq","section":"terminal","result":"undetermined","detail":"the provider could not determine source, so this cannot be treated as fully satisfying"}
    ]"""

    fun satisfaction(id: String) = when (id) {
        "d-yellowstone" -> """{"desired_item_id":"d-yellowstone","state":"FULLY_SATISFIED","content":{"satisfaction":"satisfied","assets":[{"asset_id":"01a032b7-8f86-7a18-a130-1e030d633f69","accepted":true,"score":0,"terminal":false,"reasons":$reasonsHeld}]},"placement":{"satisfaction":"unknown","detail":"","unproven":true},"upgrade":{"eligible":true,"status":"eligible","detail":"a 2160p remux would finish this want"}}"""
        else -> """{"desired_item_id":"$id","state":"MISSING","content":{"satisfaction":"not_satisfied","assets":[]},"placement":{"satisfaction":"unknown","detail":"","unproven":true},"upgrade":{"eligible":false,"status":"not_satisfied","detail":"nothing acceptable is held, so this is an acquisition rather than an upgrade"}}"""
    }

    fun candidates(id: String) = """{"desired_item_id":"$id","search_id":"s-1","candidates":[
      {"candidate_id":"infohash:bf383fadc72500cab131fbdebc996cabba44c7a2","provider":"linuxtracker","title":"Sintel 2010 1080p BluRay x264","accepted":true,"score":10,"terminal":false,"selected":true,"size_bytes":4500000000,"reasons":[
        {"rule":"resolution.gte","section":"accept","result":"pass","detail":"resolution 1080, which is at least 1080"},
        {"rule":"source.nin","section":"accept","result":"pass","detail":"source bluray, which is outside [cam, telesync]"},
        {"rule":"size_bytes.lte","section":"prefer","result":"bonus","score":10,"detail":"size_bytes 4500000000, which is at most 8589934592"}]},
      {"candidate_id":"infohash:cam","provider":"linuxtracker","title":"Sintel 2010 CAM","accepted":false,"score":0,"terminal":false,"selected":false,"reasons":[
        {"rule":"resolution.gte","section":"accept","result":"fail","detail":"resolution 480, which is not at least 1080"},
        {"rule":"source.nin","section":"accept","result":"fail","detail":"source cam, which is not outside [cam, telesync]"}]}
    ]}"""

    val explain = """{"quality_profile":"living-room","selected":"r1","ranked":[{"id":"r1","title":"Sintel 2010 1080p BluRay x264","accepted":true,"score":0,"terminal":false,"reasons":[
      {"rule":"resolution.gte","section":"accept","result":"pass","detail":"resolution 1080, which is at least 1080"},
      {"rule":"source.nin","section":"accept","result":"pass","detail":"source bluray, which is outside [cam, telesync]"},
      {"rule":"video_codec.eq","section":"prefer","result":"miss","detail":"video_codec x264, which is not equal to hevc"},
      {"rule":"hdr.eq","section":"prefer","result":"undetermined","detail":"the provider could not determine hdr"},
      {"rule":"resolution.gte","section":"terminal","result":"miss","detail":"resolution 1080, which is not at least 2160"}]},
      {"id":"r2","title":"Sintel 2010 CAM","accepted":false,"score":0,"terminal":false,"reasons":[
      {"rule":"resolution.gte","section":"accept","result":"fail","detail":"resolution 480, which is not at least 1080"},
      {"rule":"source.nin","section":"accept","result":"fail","detail":"source cam, which is not outside [cam, telesync]"}],
      "rejected_by":[{"rule":"resolution.gte","section":"accept","result":"fail","detail":"resolution 480, which is not at least 1080"},{"rule":"source.nin","section":"accept","result":"fail","detail":"source cam, which is not outside [cam, telesync]"}]}]}"""

    val renderers = """{"renderers":[{"udn":"uuid:1e055177","name":"Phantom II 95 dB-a98d","manufacturer":"Devialet","model":"Phantom II 95 dB","location":"http://192.168.16.69:45317/x.xml"},{"udn":"uuid:tv","name":"Living room TV","manufacturer":"Samsung","model":"QN85BA 55"}]}"""
    val playback = """{"elapsed_seconds":1425,"duration_seconds":5520,"playing":true,"renderer":"Living room TV","state":"PLAYING","title":"Yellowstone — S04E02 Phantom Pain"}"""
    val jobs = """{"items":[
      {"id":"j1","type":"search_release","state":"succeeded","attempts":1,"last_error":null,"updated_at":"2026-09-07T20:41:10Z"},
      {"id":"j2","type":"ingest_artifact","state":"running","attempts":1,"last_error":null,"updated_at":"2026-09-07T20:40:02Z"},
      {"id":"j3","type":"probe_blob","state":"dead","attempts":5,"last_error":"ffprobe: moov atom not found","updated_at":"2026-09-07T19:12:44Z"},
      {"id":"j4","type":"provider_health","state":"succeeded","attempts":1,"last_error":null,"updated_at":"2026-09-07T20:38:00Z"}
    ]}"""
    val peers = """{"count":1,"truncated":false,"peers":[{"peer_id":"01a01df7-2472-7a96-b9d7-73da4e8a7355","name":"hyperion-1","site":"bartley-ridge","mode":"full","is_self":true}],"note":"More than one peer is supported and proven (M4), and so is exactly one: a single peer here is a deployment choice, not a symptom."}"""
    val replicas = """{"blob_hash":"$HASH","replicas":[{"peer":"hyperion-1","state":"present","verified":true}]}"""
    val externalIds = """{"external_ids":[{"source":"tvdb","value":"341164"},{"source":"imdb","value":"tt4236770"}]}"""

    fun workDetail(id: String): String {
        val w = works.firstOrNull { JsonScan.stringField(it, "id") == id } ?: return "{}"
        val hasFile = id in setOf(YELLOWSTONE, "w-dune", "w-kid-a", "w-piranesi")
        val type = JsonScan.stringField(w, "content_type")
        val (mime, size) = when (type) {
            "book" -> "application/epub+zip" to 1_842_000L
            "music" -> "audio/flac" to 412_000_000L
            else -> "video/mp4" to 2_986_000_000L
        }
        val primary = if (hasFile) """{"asset_id":"01a032b7-8f86-7a18-a130-1e030d633f69","edition_id":"e1","blob_hash":"$HASH","mime":"$mime","size":$size,"content_url":"/api/v1/blobs/$HASH/content"}""" else "null"
        return w.dropLast(1) + ""","external_ids":{},"primary_asset":$primary}"""
    }

    private val S4 =
        listOf("Half the Money", "Phantom Pain", "All I See Is You", "Winning or Learning", "Under a Blanket of Red", "I Want to Be Him", "Keep the Wolves Close", "No Kindness for the Coward", "No Such Thing as Fair", "Grass on the Streets and Weeds on the Rooftops")
    private val S5 =
        listOf(
            "One Hundred Years Is Nothing",
            "The Sting of Wisdom",
            "Tall Drink of Water",
            "Horses in Heaven",
            "Watch 'Em Ride Away",
            "Cigarettes Whiskey a Meadow and You",
            "The Dream Is Not Me",
        )

    private fun ep(season: Int, n: Int, title: String, held: Boolean): String {
        val code = "S%02dE%02d".format(season, n)
        val stem = "Yellowstone (2018) - $code - $title [HDTV-1080p][AC3 5.1][x264]"
        val label = "Season %02d".format(season)
        val thumb = """{"id":"th-$code","edition_id":"e$season","blob_hash":"$HASH","filename":"$stem-thumb.jpg","mime":"image/jpeg","role":"artwork","blob_size":48511,"edition_label":"$label"}"""
        val video = if (held) """,{"id":"as-$code","edition_id":"e$season","blob_hash":"$HASH","filename":"$stem.mp4","mime":"video/mp4","role":"primary","blob_size":${1_300_000_000L + n * 37_000_000L},"edition_label":"$label"}""" else ""
        val sub = if (held &&
            n == 1
        ) {
            """,{"id":"sub-$code","edition_id":"e$season","blob_hash":"$HASH","filename":"$stem.en.srt","mime":"text/plain","role":"subtitle","blob_size":61234,"edition_label":"$label"}"""
        } else {
            ""
        }
        return thumb + video + sub
    }

    fun assets(id: String): String {
        if (id != YELLOWSTONE) return """{"items":[]}"""
        val rows = S4.mapIndexed { i, t -> ep(4, i + 1, t, held = true) } +
            S5.mapIndexed { i, t -> ep(5, i + 1, t, held = i != 4 && i != 5) }
        return "{\"items\":[" + rows.joinToString(",") + "]}"
    }

    /** The node's continue rail: one unfinished session on Yellowstone S04E03. */
    val continueRail = """{"items":[{"session":{"id":"cs-1","asset_id":"as-S04E03","device_id":"dev-1","verb":"watch","state":"paused","progress":{"locator":"1425","unit":"seconds"},"created_at":"2026-09-06T20:00:00Z","updated_at":"2026-09-06T20:41:00Z","started_at":"2026-09-06T20:00:00Z","ended_at":null},
      "work":{"id":"$YELLOWSTONE","content_type":"series","title":"Yellowstone","year":2018,"artwork":{"asset_id":"a","blob_hash":"$HASH","mime":"image/jpeg","content_url":"/api/v1/blobs/$HASH/content"}},
      "edition":{"id":"e4","label":"Season 04","attributes":{}},
      "asset":{"asset_id":"as-S04E03","edition_id":"e4","blob_hash":"$HASH","mime":"video/mp4","size":1411000000,"duration_seconds":3312.5,"content_url":"/api/v1/blobs/$HASH/content"}}]}"""

    /** JSON-RPC envelope around a tool result. */
    fun rpc(text: String) = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":${quote(text)}}]}}"""
    fun rpcError(message: String, tool: String) =
        """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":${quote(message)},"data":{"tool":"$tool"}}}"""
    private fun quote(s: String) = buildString { one.rarebit.heyarr.core.mcp.JsonWrite.writeString(this, s) }
}

/** A fake transport answering from [Fixtures]; records every request for assertions. */
class FakeHeyarrTransport(private val delayMs: Long = 0) : HttpTransport {
    val requests = ArrayList<Pair<String, String?>>()

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        requests.add(url to null)
        if (delayMs > 0) Thread.sleep(delayMs)
        val path = url.substringAfter("/api/v1/").substringBefore('?')
        val body = when {
            path == "works" -> Fixtures.worksList()

            path == "quality-profiles" -> Fixtures.profiles

            path == "desired" -> Fixtures.desired

            path == "consumption/continue" -> Fixtures.continueRail

            path == "jobs" -> Fixtures.jobs

            path == "session" -> """{"kind":"service","principal_id":"01a07aaf-da04-7e12-9228-cb645d86fc6c","scopes":["write"],"can_write":true,"management_authorized":false}"""

            path == "providers" -> """{"providers":[{"name":"linuxtracker","capabilities":["indexer"],"healthy":true,"detail":"reachable — Prowlarr","version":"unreported","checked_at":"2026-09-07T14:19:19Z"},{"name":"transmission","capabilities":["download"],"healthy":true,"detail":"reachable","version":"4.1.3","checked_at":"2026-09-07T14:19:19Z"},{"name":"internet-archive","capabilities":["indexer"],"healthy":false,"detail":"the indexer is rate limiting","checked_at":"2026-09-07T14:19:19Z"}],"capabilities":["indexer","download"]}"""

            path == "capabilities" -> """{"holders":[{"worker_id":"hyperion/19403/01a0723e","peer_id":"p1","peer_name":"hyperion-1","capabilities":[{"name":"download"},{"name":"ffmpeg"},{"name":"ffmpeg.encoder.h264"},{"name":"ffprobe"},{"name":"indexer"}],"expires_at":"2026-09-07T14:30:18Z"}],"available":["download","ffmpeg","ffmpeg.encoder.h264","ffprobe","indexer"]}"""

            path == "libraries" -> """{"items":[{"id":"l1","name":"shows","content_type":"show","enabled":true,"roots":[{"path":"/srv/nas-seed/media/tvseries"}]},{"id":"l2","name":"films","content_type":"movie","enabled":true,"roots":[{"path":"/srv/nas-seed/media/movies"}]}]}"""

            path == "jobs" -> """{"items":[{"id":"j1","type":"scan_library","state":"succeeded","attempts":1,"last_error":null,"updated_at":"2026-09-07T12:01:00Z"},{"id":"j2","type":"search_releases","state":"failed","attempts":3,"last_error":"indexer timeout","updated_at":"2026-09-07T11:40:00Z"},{"id":"j3","type":"verify_blob","state":"queued","attempts":0,"last_error":null,"updated_at":"2026-09-07T11:38:00Z"}]}"""

            path.startsWith(
                "followed-sources/",
            ) && path.endsWith(
                "/items",
            ) -> """{"items":[{"id":"fi-1","title":"Post-quantum by default","work_id":"w-cf-1","item_key":"2026-09-05","published_at":"2026-09-05T10:00:00Z","archived":true},{"id":"fi-2","title":"Workers AI: what shipped this month","work_id":"w-cf-2","item_key":"2026-09-02","published_at":"2026-09-02T09:30:00Z","archived":false}]}"""

            path.startsWith(
                "desired/",
            ) && path.endsWith(
                "/candidates",
            ) -> Fixtures.candidates(path.removePrefix("desired/").removeSuffix("/candidates"))

            path.startsWith(
                "works/",
            ) && path.endsWith("/assets") -> Fixtures.assets(path.removePrefix("works/").removeSuffix("/assets"))

            path.startsWith("works/") -> Fixtures.workDetail(path.removePrefix("works/"))

            else -> return HttpResponse(404, """{"title":"Not Found","detail":"no route matches /api/v1/$path"}""")
        }
        return HttpResponse(200, body)
    }

    override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>): HttpResponse {
        requests.add(url to body)
        if (delayMs > 0) Thread.sleep(delayMs)
        val root = body?.let { JsonScan.rootObject(it) } ?: return HttpResponse(400, "")
        val params = JsonScan.objectAt(root, "params") ?: return HttpResponse(400, "")
        val tool = JsonScan.stringField(params, "name") ?: return HttpResponse(400, "")
        val args = JsonScan.objectAt(params, "arguments") ?: "{}"
        val text = when (tool) {
            "search_content" -> Fixtures.searchContent(
                JsonScan.stringField(args, "query"),
                JsonScan.stringField(args, "content_type"),
            )

            "list_followed" -> Fixtures.followed

            "get_missing_content" -> Fixtures.missing

            "get_upgrade_candidates" -> Fixtures.upgrades

            "get_content_satisfaction" -> Fixtures.satisfaction(JsonScan.stringField(args, "desired_item_id") ?: "")

            "explain_release" -> Fixtures.explain

            "list_renderers" -> Fixtures.renderers

            "playback_status" -> Fixtures.playback

            "get_peer_status" -> Fixtures.peers

            "get_replica_status" -> Fixtures.replicas

            "get_external_ids" -> Fixtures.externalIds

            "want_content" -> """{"desired_item_id":"d-new","work_id":"${JsonScan.stringField(
                args,
                "work_id",
            ) ?: "w-new"}","state":"MISSING","title":"${JsonScan.stringField(args, "title") ?: ""}"}"""

            "monitor_content", "control_playback", "play_here", "unfollow" -> """{"ok":true}"""

            "search_releases", "verify_blob", "sync_peer" -> """{"job":{"id":"job-1","state":"queued"}}"""

            "discover_content" -> return HttpResponse(
                200,
                Fixtures.rpcError(
                    "no metadata provider is configured that can search for new content — configure a TVDB provider (ADR-0058) to enable discovery",
                    tool,
                ),
            )

            "acquire_release" -> return HttpResponse(
                200,
                Fixtures.rpcError(
                    "candidate rejected by rule source.nin: source cam, which is not outside [cam, telesync] — change the profile if it should be acceptable",
                    tool,
                ),
            )

            "follow_source" -> """{"id":"fs-new","title":"${JsonScan.stringField(
                args,
                "title",
            ) ?: JsonScan.stringField(
                args,
                "url",
            ) ?: "new"}","type":"podcast","items_known":0,"items_archived":0,"health":"unknown"}"""

            else -> return HttpResponse(200, Fixtures.rpcError("unknown tool", tool))
        }
        return HttpResponse(200, Fixtures.rpc(text))
    }
}
