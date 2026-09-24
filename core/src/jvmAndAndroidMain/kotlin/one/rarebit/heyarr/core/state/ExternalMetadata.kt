package one.rarebit.heyarr.core.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.mcp.JsonWrite
import one.rarebit.heyarr.core.net.JsonArrays
import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.core.theme.MediaType
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

/** What a public source could tell us about a work: a cover, a synopsis, and where it came from. */
data class ExternalMeta(
    val imageUrl: String? = null,
    val landscapeImageUrl: String? = null,
    val synopsis: String? = null,
    val source: String,
    val sourceUrl: String? = null,
    /** TVmaze's show id, for the episode list. */
    val tvmazeId: Long? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = imageUrl == null && synopsis == null
}

/** An episode as TVmaze lists it — the calendar the node has no provider for. */
data class ExternalEpisode(
    val season: Int,
    val number: Int,
    val name: String?,
    val airdate: String?,
    val summary: String?,
    val imageUrl: String?,
)

/** The identity a lookup is made from. Whatever the library knows; nulls are fine. */
data class MetaKey(
    val type: MediaType,
    val title: String,
    val year: Int? = null,
    val creator: String? = null,
    val feedRef: String? = null,
)

/**
 * Cover art and synopses from public, keyless sources — used only where the node
 * holds nothing, and always labelled as external in the UI. One resolver per media
 * kind; every answer (including "nothing found") is cached on disk under [cacheDir] so
 * a title is asked about at most once a week. Look-ups are single-flighted per key and
 * rate-limited per host (MusicBrainz asks for 1 req/s and a real User-Agent; each app's
 * [fetch] sends one).
 *
 * The network is a seam: each app supplies [fetch] over its own bare HTTP client (never
 * the authenticated one — the node's credential must not travel to these hosts) and its
 * own cache directory. The desktop also supplies [movieLookup] (a movie's artwork from the
 * node's own discovery provider) and keeps its movie answers under a separate cache key.
 *
 * The parsing is split into pure functions in [ExternalParsers] and unit-tested; the
 * network is the only thing that is not.
 */
class ExternalMetadata(
    private val cacheDir: File,
    private val enabled: () -> Boolean = { true },
    private val fetch: (String) -> String?,
    /** A movie look-up tried before Wikipedia; its presence also versions the movie cache key. */
    private val movieLookup: ((MetaKey) -> ExternalMeta?)? = null,
) {
    private val inFlight = HashMap<String, Mutex>()
    private val hostLocks = HashMap<String, Mutex>()
    private val lastHit = HashMap<String, Long>()
    private val memory = HashMap<String, ExternalMeta?>()

    suspend fun lookup(key: MetaKey): ExternalMeta? {
        if (!enabled()) return null
        val id = cacheKey(key)
        synchronized(memory) { if (memory.containsKey(id)) return memory[id] }
        val gate = synchronized(inFlight) { inFlight.getOrPut(id) { Mutex() } }
        return gate.withLock {
            synchronized(memory) { if (memory.containsKey(id)) return@withLock memory[id] }
            val cached = readCache(id)
            if (cached != null &&
                !stale(cached)
            ) {
                return@withLock cached.also { synchronized(memory) { memory[id] = it } }
            }
            val fresh = withContext(Dispatchers.IO) { runCatching { resolve(key) }.getOrNull() }
            val value = fresh ?: ExternalMeta(source = "none")
            writeCache(id, value)
            synchronized(memory) { memory[id] = value }
            value
        }
    }

    /** TVmaze's episode list for a show found by [lookup]; empty when there is none. */
    suspend fun episodes(tvmazeId: Long): List<ExternalEpisode> {
        if (!enabled()) return emptyList()
        val id = "tvmaze-episodes:$tvmazeId"
        val f = File(cacheDir, "$id.json")
        val cached = runCatching {
            if (f.isFile &&
                System.currentTimeMillis() - f.lastModified() < TTL_MS
            ) {
                f.readText()
            } else {
                null
            }
        }.getOrNull()
        val body =
            cached
                ?: withContext(Dispatchers.IO) {
                    get("https://api.tvmaze.com/shows/$tvmazeId/episodes")
                }?.also {
                    runCatching {
                        cacheDir.mkdirs()
                        f.writeText(it)
                    }
                }
                ?: return emptyList()
        return ExternalParsers.tvmazeEpisodes(body)
    }

    private suspend fun resolve(key: MetaKey): ExternalMeta? = when (key.type) {
        MediaType.SERIES -> get("https://api.tvmaze.com/singlesearch/shows?q=${enc(key.title)}")?.let { body ->
            val show = ExternalParsers.tvmazeShow(body) ?: return@let null
            val landscape = show.tvmazeId?.let { id ->
                get(
                    "https://api.tvmaze.com/shows/$id/images",
                )?.let(one.rarebit.heyarr.core.state.LandscapeArtwork::tvmaze)
            }
            show.copy(landscapeImageUrl = landscape)
        }

        MediaType.MOVIE -> movieLookup?.invoke(key) ?: wikipedia(key)

        MediaType.BOOK -> get(
            "https://openlibrary.org/search.json?title=${enc(key.title)}${key.creator?.let {
                "&author=${enc(it)}"
            } ?: ""}&limit=1&fields=title,author_name,cover_i,first_publish_year,first_sentence",
        )?.let {
            ExternalParsers.openLibrary(it)
        }

        MediaType.MUSIC -> musicBrainz(key)

        MediaType.PODCAST, MediaType.AUDIOBOOK -> get(
            "https://itunes.apple.com/search?term=${enc(key.title)}&media=podcast&limit=1",
        )?.let {
            ExternalParsers.itunes(it)
        }
            ?: feedImage(key)

        MediaType.FEED -> feedImage(key)

        MediaType.UNKNOWN -> null
    }

    private suspend fun wikipedia(key: MetaKey): ExternalMeta? {
        val titles = listOfNotNull(key.year?.let { "${key.title} ($it film)" }, "${key.title} (film)", key.title)
        for (t in titles) {
            val body = get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(t.replace(' ', '_'))}") ?: continue
            val meta = ExternalParsers.wikipedia(body) ?: continue
            if (meta.isEmpty) continue
            // A film page that names a different year is a different film ("Yellowstone (1936 film)" for a 2018 work).
            if (!ExternalParsers.yearAgrees(meta.synopsis, key.year)) continue
            return meta
        }
        return null
    }

    private suspend fun musicBrainz(key: MetaKey): ExternalMeta? {
        val q = buildString {
            append("release:\"${key.title}\"")
            key.creator?.let { append(" AND artist:\"$it\"") }
        }
        val body = get("https://musicbrainz.org/ws/2/release-group/?query=${enc(q)}&fmt=json&limit=1") ?: return null
        val mbid = ExternalParsers.musicBrainzReleaseGroup(body) ?: return null
        return ExternalMeta(
            imageUrl = "https://coverartarchive.org/release-group/$mbid/front-500",
            source = "Cover Art Archive",
            sourceUrl = "https://musicbrainz.org/release-group/$mbid",
        )
    }

    private suspend fun feedImage(key: MetaKey): ExternalMeta? {
        val ref = key.feedRef?.takeIf { it.startsWith("http") } ?: return null
        val host = runCatching { URI(ref).host }.getOrNull() ?: return null
        val xml = get(ref)
        val fromFeed = xml?.let { ExternalParsers.feedImage(it) }
        return ExternalMeta(
            imageUrl = fromFeed ?: "https://icons.duckduckgo.com/ip3/$host.ico",
            synopsis = xml?.let {
                ExternalParsers.feedDescription(it)
            },
            source = if (fromFeed !=
                null
            ) {
                "the feed"
            } else {
                "site icon"
            },
            sourceUrl = ref,
        )
    }

    private suspend fun get(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull() ?: return null
        val lock = synchronized(hostLocks) { hostLocks.getOrPut(host) { Mutex() } }
        return lock.withLock {
            val wait = synchronized(lastHit) { (lastHit[host] ?: 0L) + PER_HOST_MS - System.currentTimeMillis() }
            if (wait > 0) kotlinx.coroutines.delay(wait)
            synchronized(lastHit) { lastHit[host] = System.currentTimeMillis() }
            withContext(Dispatchers.IO) { fetch(url) }
        }
    }

    // ── cache ────────────────────────────────────────────────────────────────────

    private fun cacheKey(key: MetaKey): String {
        val version = if (key.type == MediaType.MOVIE && movieLookup != null) "movie-artwork-v3" else "artwork-v2"
        val parts = listOf(
            version,
            key.type.name,
            key.title.lowercase().trim(),
            key.year?.toString() ?: "",
            key.creator?.lowercase() ?: "",
            key.feedRef ?: "",
        )
        val raw = parts.joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") {
            "%02x".format(it)
        }.take(32)
    }

    private fun stale(m: ExternalMeta): Boolean {
        val ttl = if (m.isEmpty) NEGATIVE_TTL_MS else TTL_MS
        return System.currentTimeMillis() - m.fetchedAt > ttl
    }

    private fun readCache(id: String): ExternalMeta? = runCatching {
        val f = File(cacheDir, "$id.json")
        if (!f.isFile) return null
        val o = JsonScan.rootObject(f.readText()) ?: return null
        ExternalMeta(
            imageUrl = JsonScan.stringField(o, "image"),
            landscapeImageUrl = JsonScan.stringField(o, "landscape_image"),
            synopsis = JsonScan.stringField(o, "synopsis"),
            source =
            JsonScan.stringField(o, "source") ?: "none",
            sourceUrl = JsonScan.stringField(o, "source_url"),
            tvmazeId = JsonScan.longField(o, "tvmaze_id"),
            fetchedAt =
            JsonScan.longField(o, "fetched_at") ?: 0L,
        )
    }.getOrNull()

    private fun writeCache(id: String, m: ExternalMeta) {
        runCatching {
            cacheDir.mkdirs()
            File(cacheDir, "$id.json").writeText(
                JsonWrite.obj(
                    linkedMapOf(
                        "image" to m.imageUrl,
                        "landscape_image" to m.landscapeImageUrl,
                        "synopsis" to m.synopsis,
                        "source" to m.source,
                        "source_url" to m.sourceUrl,
                        "tvmaze_id" to m.tvmazeId,
                        "fetched_at" to m.fetchedAt,
                    ),
                ),
            )
        }
    }

    companion object {
        /** What every fetcher sends as `Accept`: the sources answer JSON, RSS/Atom or plain XML. */
        const val ACCEPT = "application/json, application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.5"
        private const val TTL_MS = 7L * 24 * 3600 * 1000
        private const val NEGATIVE_TTL_MS = 24L * 3600 * 1000
        private const val PER_HOST_MS = 1100L

        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

/** Pure readers over each source's JSON / XML. */
object ExternalParsers {
    fun tvmazeShow(body: String): ExternalMeta? {
        val o = JsonScan.rootObject(body) ?: return null
        val image = JsonScan.objectAt(o, "image")?.let {
            JsonScan.stringField(it, "original")
                ?: JsonScan.stringField(it, "medium")
        }
        return ExternalMeta(
            imageUrl = image,
            synopsis = JsonScan.stringField(o, "summary")?.let(::stripHtml),
            source = "TVmaze",
            sourceUrl = JsonScan.stringField(o, "url"),
            tvmazeId = JsonScan.longField(o, "id"),
        )
    }

    fun tvmazeEpisodes(body: String): List<ExternalEpisode> = JsonScan.objectsOf(body, emptyList()).mapNotNull { e ->
        val season = JsonScan.intField(e, "season") ?: return@mapNotNull null
        val number = JsonScan.intField(e, "number") ?: return@mapNotNull null
        ExternalEpisode(
            season,
            number,
            JsonScan.stringField(e, "name"),
            JsonScan.stringField(e, "airdate"),
            JsonScan.stringField(e, "summary")?.let(::stripHtml),
            JsonScan.objectAt(e, "image")?.let {
                JsonScan.stringField(it, "medium")
                    ?: JsonScan.stringField(it, "original")
            },
        )
    }

    fun wikipedia(body: String): ExternalMeta? {
        val o = JsonScan.rootObject(body) ?: return null
        if (JsonScan.stringField(o, "type") == "disambiguation") return null
        val image = JsonScan.objectAt(o, "thumbnail")?.let { JsonScan.stringField(it, "source") }?.substringBefore("?")
        val extract = JsonScan.stringField(o, "extract")
        val page = JsonScan.objectAt(o, "content_urls")?.let {
            JsonScan.objectAt(it, "desktop")
        }?.let { JsonScan.stringField(it, "page") }
        return ExternalMeta(imageUrl = image, synopsis = extract, source = "Wikipedia", sourceUrl = page)
    }

    fun openLibrary(body: String): ExternalMeta? {
        val doc = JsonScan.objectsOf(body, listOf("docs")).firstOrNull() ?: return null
        val cover = JsonScan.longField(doc, "cover_i") ?: return null
        val sentence = JsonScan.arrayOf(doc, listOf("first_sentence"))?.let {
            JsonArrays.parseStrings(it).firstOrNull()
        }
        return ExternalMeta(
            imageUrl = "https://covers.openlibrary.org/b/id/$cover-L.jpg",
            synopsis = sentence,
            source = "Open Library",
            sourceUrl = "https://covers.openlibrary.org/b/id/$cover.json",
        )
    }

    fun itunes(body: String): ExternalMeta? {
        val r = JsonScan.objectsOf(body, listOf("results")).firstOrNull() ?: return null
        val art = JsonScan.stringField(r, "artworkUrl600") ?: JsonScan.stringField(r, "artworkUrl100") ?: return null
        return ExternalMeta(
            imageUrl = art,
            source = "Apple Podcasts",
            sourceUrl = JsonScan.stringField(r, "collectionViewUrl"),
        )
    }

    fun musicBrainzReleaseGroup(body: String): String? {
        val group = JsonScan.objectsOf(body, listOf("release-groups")).firstOrNull()
        return group?.let { JsonScan.stringField(it, "id") }
    }

    /** `<image><url>…</url></image>`, `<itunes:image href="…">`, or a `<logo>` / `<icon>` in Atom. */
    fun feedImage(xml: String): String? {
        RE_ITUNES_IMAGE.find(xml)?.groupValues?.get(1)?.let { return it.trim() }
        RE_IMAGE_URL.find(xml)?.groupValues?.get(1)?.trim()?.takeIf {
            it.isNotEmpty() && !it.endsWith("favicon.ico")
        }?.let { return it }
        RE_ATOM_LOGO.find(xml)?.groupValues?.get(1)?.trim()?.let { return it }
        RE_IMAGE_URL.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    fun feedDescription(xml: String): String? {
        val raw = RE_DESCRIPTION.find(xml)?.groupValues?.get(1) ?: RE_SUBTITLE.find(xml)?.groupValues?.get(1)
        return raw?.let { stripHtml(it) }?.takeIf { it.isNotBlank() }?.take(400)
    }

    /** True unless the text's first four-digit year sits more than a year from [year]. */
    fun yearAgrees(text: String?, year: Int?): Boolean {
        if (year == null || text == null) return true
        val found = RE_YEAR_IN_TEXT.find(text)?.value?.toIntOrNull() ?: return true
        return kotlin.math.abs(found - year) <= 1
    }

    fun stripHtml(s: String): String = s.replace(
        RE_TAGS,
        "",
    ).replace(
        "&amp;",
        "&",
    ).replace(
        "&quot;",
        "\"",
    ).replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(RE_WS, " ").trim()

    private val RE_TAGS = Regex("<!\\[CDATA\\[|]]>|<[^>]+>")
    private val RE_YEAR_IN_TEXT = Regex("""\b(19|20)\d{2}\b""")
    private val RE_WS = Regex("\\s+")
    private val RE_ITUNES_IMAGE = Regex("""<itunes:image[^>]*href="([^"]+)"""")
    private val RE_IMAGE_URL =
        Regex("""<image>\s*(?:<[^>]+>[^<]*</[^>]+>\s*)*?<url>\s*([^<]+?)\s*</url>""", RegexOption.DOT_MATCHES_ALL)
    private val RE_ATOM_LOGO = Regex("""<(?:logo|icon)>\s*([^<]+?)\s*</(?:logo|icon)>""")
    private val RE_DESCRIPTION = Regex("""<channel>.*?<description>(.*?)</description>""", RegexOption.DOT_MATCHES_ALL)
    private val RE_SUBTITLE =
        Regex("""<(?:subtitle|itunes:summary)>(.*?)</(?:subtitle|itunes:summary)>""", RegexOption.DOT_MATCHES_ALL)
}
