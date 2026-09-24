package one.rarebit.heyarr.core.theme

/**
 * The media kinds the UI knows how to skin. heyarr's library reports `movie`, `series`,
 * `music`, `book` (and `document` / `unknown` for scanned-but-unclassified works);
 * followed sources add `tv_series`, `podcast`, `youtube_channel` and `rss_feed`. The
 * [apiName] is what `search_content` / `want_content` take as `content_type` — only
 * the four the tool accepts have one; the rest are display-only kinds.
 *
 * This is the pure, Compose-free half of the media model — it lives in `:core` so the
 * domain graph (search grouping, library status, the API layer) can depend on it without
 * pulling in the Compose runtime. The accent/aspect theme table that skins each kind is
 * `MediaThemes` in `:ui`.
 */
enum class MediaType(val apiName: String?, val label: String, val plural: String) {
    MOVIE("movie", "Movie", "Movies"),
    SERIES("series", "Series", "Series"),
    BOOK("book", "Book", "Books"),
    AUDIOBOOK(null, "Audiobook", "Audiobooks"),
    PODCAST(null, "Podcast", "Podcasts"),
    MUSIC("music", "Music", "Music"),
    FEED(null, "Feed", "Feeds & articles"),
    UNKNOWN(null, "Other", "Other"),
    ;

    /** The kinds that are searchable server-side (a `content_type` value). */
    val searchable: Boolean get() = apiName != null

    companion object {
        /** The search fan-out order — also the order sections appear in mixed results. */
        val SEARCHABLE: List<MediaType> = listOf(MOVIE, SERIES, MUSIC, BOOK)

        /** Map any heyarr type word (work `content_type` or followed-source `type`) to a kind; unknown → [UNKNOWN]. */
        fun from(raw: String?): MediaType = when (raw?.trim()?.lowercase()) {
            "movie", "film" -> MOVIE
            "series", "tv", "tv_series", "show", "episode" -> SERIES
            "book", "ebook", "epub", "comic" -> BOOK
            "audiobook" -> AUDIOBOOK
            "podcast", "podcast_episode" -> PODCAST
            "music", "album", "track", "artist" -> MUSIC
            "rss_feed", "feed", "article", "document", "youtube_channel", "video" -> FEED
            else -> UNKNOWN
        }
    }
}
