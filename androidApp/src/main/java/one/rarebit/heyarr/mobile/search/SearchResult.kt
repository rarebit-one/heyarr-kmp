package one.rarebit.heyarr.mobile.search

/**
 * One hit from a **source-agnostic content search**. The user types what they want
 * (a show, a movie, a podcast, a channel); heyarr resolves it against the catalogue
 * and returns works — this is a thin projection of one, deliberately mirroring
 * [one.rarebit.heyarr.mobile.library.Work] but carrying the extra fields a "what do I
 * want to acquire" list needs (a [year] to disambiguate, the [artworkPath] poster).
 *
 * Crucially there is **no indexer/source field**: the user expresses intent, the
 * server routes to sources. A result is something you can then **Get once** (a
 * one-off acquisition) or **Follow** (an ongoing subscription) — see [AcquireClient].
 *
 * [workId] is heyarr's work id (from `GET /api/v1/works`), the handle a one-off want
 * points at. [type] is the server's `content_type` (movie, series, podcast, …) and
 * is the hint for whether Following (ongoing) makes sense.
 */
data class SearchResult(
    val workId: String,
    val title: String,
    val type: String? = null,
    val year: Int? = null,
    /**
     * The work's stored TVDB external id, when the server has one (heyarr-core
     * `WorkSummary.tvdb_id`, ADR-0050/0061). This is the **feed identity** a follow
     * needs: `FollowSourceRequest` takes a `tvdb_id`, so a search hit that carries one
     * can be followed in a single tap ([AcquireClient.follow] passes it through).
     * `/search` is library-local, so a work not yet carrying a stored id has none and
     * following it still needs a URL — that gap is the server's to close, not this
     * client's.
     */
    val tvdbId: String? = null,
    /** The poster's blob route, relative to the node, from the `artwork` embed (ADR-0075). */
    val artworkPath: String? = null,
) {
    /**
     * A hint — not a hard rule — for whether an ongoing **Follow** is meaningful:
     * series/shows/podcasts/channels/feeds emit new items over time, a movie is a
     * one-off. The UI surfaces both actions regardless; this only steers emphasis.
     */
    val followable: Boolean
        get() = type?.lowercase()?.let { t ->
            FOLLOWABLE_TYPES.any { t.contains(it) }
        } ?: false

    private companion object {
        val FOLLOWABLE_TYPES = listOf("series", "show", "season", "episode", "podcast", "channel", "feed")
    }
}

/**
 * A part of a work that matched by its own title (heyarr-core ADR-0075 `EpisodeHit`):
 * a scanned episode — an edition with its file — or an item a followed source
 * projected, which has no file of its own. Playable iff it carries a blob.
 */
data class EpisodeResult(
    val kind: String,
    val id: String,
    val workId: String,
    val workTitle: String,
    val contentType: String? = null,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val assetId: String? = null,
    val blobHash: String? = null,
    val mime: String? = null,
) {
    val isPlayable: Boolean get() = !blobHash.isNullOrBlank()

    /** `S01E02` when both numbers are known, else whichever is. */
    val code: String? get() = when {
        season != null && episode != null -> "S%02dE%02d".format(season, episode)
        episode != null -> "E$episode"
        season != null -> "S$season"
        else -> null
    }
}

/** Both halves of a search answer. */
data class SearchHits(val works: List<SearchResult>, val episodes: List<EpisodeResult>)
