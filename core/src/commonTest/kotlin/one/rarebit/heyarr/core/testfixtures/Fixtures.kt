package one.rarebit.heyarr.core.testfixtures

import one.rarebit.heyarr.core.net.JsonScan

/**
 * Pure, Compose-free canned heyarr answers — the subset the `:core` parser/derivation
 * tests need (McpModels, LibraryStatus, SearchGrouping). The shapes mirror what a live
 * node returns, trimmed.
 *
 * This is deliberately a small copy of the members the moved tests consume, NOT the whole
 * app-side `preview.Fixtures`. That richer object stays in `:composeApp/jvmMain` because
 * it also drives the screenshot renderer and carries a `FakeHeyarrTransport`
 * (`Thread.sleep`) plus a `String.format`-based episode builder — neither of which belongs
 * in `commonTest`, and `jvmMain` cannot see this test source set to delegate to it. If the
 * duplication ever grows, a dedicated shared `:test-fixtures` module would fold them back
 * together.
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
        work("w-dune", "movie", "Dune: Part Two", 2024, art = true, attrs = """{"runtime":"166 min","genre":"Sci-fi"}"""),
        work("w-severance", "series", "Severance", 2022, art = true),
        work("w-piranesi", "book", "Piranesi", 2020, attrs = """{"author":"Susanna Clarke","pages":"245"}"""),
        work("w-dune-book", "book", "Dune", 1965, attrs = """{"author":"Frank Herbert","pages":"412","series":"Dune"}"""),
        work("w-kid-a", "music", "Kid A", 2000, attrs = """{"artist":"Radiohead","album":"Kid A"}"""),
        work("w-blue", "music", "Blue", 1971, attrs = """{"artist":"Joni Mitchell"}"""),
        work("w-project-hail", "book", "Project Hail Mary", 2021, attrs = """{"author":"Andy Weir","narrator":"Ray Porter"}"""),
        work("w-cloudflare", "document", "Cloudflare Blog", null),
        work("w-ys-s4", "series", "Yellowstone Season 4 Mp4", null),
    )

    fun searchContent(query: String?, type: String?): String {
        val q = query?.lowercase().orEmpty()
        val hits = works.filter { w ->
            val title = JsonScan.stringField(w, "title")!!.lowercase()
            val t = JsonScan.stringField(w, "content_type")
            (type == null || t == type) && (q.isEmpty() || title.contains(q)) && t != "document"
        }
        val episodes = if (q.isNotEmpty() && "yellowstone".contains(q) && type == null)
            """{"id":"ep-1","kind":"edition","title":"S04E02 — Phantom Pain","work_id":"$YELLOWSTONE","work_title":"Yellowstone","content_type":"series","primary_asset":{"asset_id":"as-1","blob_hash":"$HASH"}}""" else ""
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

    val desired = """{"items":[
      {"id":"$SINTEL_WANT","scope":"work","work_id":"$SINTEL","quality_profile_id":"qp-living","monitor":true,"reason":"session validation","acquisition":{"state":"MISSING","phase":"idle","managed":false,"content":"not_satisfied","placement":"unknown","detail":"1 of 2 indexer(s) answered with nothing; 1 could not be reached"}},
      {"id":"d-piranesi","scope":"work","work_id":"w-piranesi","quality_profile_id":"qp-everyday","monitor":true,"reason":"book club","acquisition":{"state":"SELECTED","phase":"fetching","managed":true,"content":"not_satisfied","placement":"unknown","detail":"1 candidate(s) selected"}},
      {"id":"d-kid-a","scope":"work","work_id":"w-kid-a","quality_profile_id":"qp-archival","monitor":true,"reason":null,"acquisition":{"state":"MISSING","phase":"idle","managed":false,"content":"not_satisfied","placement":"unknown","detail":"1 candidate(s), none acceptable"}},
      {"id":"d-yellowstone","scope":"work","work_id":"$YELLOWSTONE","quality_profile_id":"qp-living","monitor":true,"reason":"validate","acquisition":{"state":"FULLY_SATISFIED","phase":"idle","managed":true,"content":"satisfied","placement":"unknown","detail":""}},
      {"id":"d-dune","scope":"work","work_id":"w-dune","quality_profile_id":"qp-living","monitor":false,"reason":null,"acquisition":{"state":"AVAILABLE","phase":"idle","managed":true,"content":"satisfied","placement":"unknown","detail":""}}
    ],"next_cursor":null}"""

    val profiles = """{"items":[
      {"id":"qp-archival","name":"archival","description":"Keep the best there is. Never terminal: there is no condition under which this profile stops looking for something better.","content_types":["movie","series"]},
      {"id":"qp-everyday","name":"everyday","description":"A laptop or a tablet. Accepts 720p and up and is finished at 1080p — smaller files, reached sooner.","content_types":["movie","series"]},
      {"id":"qp-living","name":"living-room","description":"The big screen. Accepts 1080p and up, prefers HEVC/HDR/surround, finished at a 2160p remux."}
    ]}"""

    private val reasonsHeld = """[
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
}
