package one.rarebit.heyarr.mobile.state

import one.rarebit.heyarr.core.mcp.EpisodeHit
import one.rarebit.heyarr.core.mcp.SearchHit
import one.rarebit.heyarr.mobile.search.FollowedSource
import one.rarebit.heyarr.core.theme.MediaType

/** A search result of any kind, flattened for the list. Ported from heyarr-desktop's `state/SearchGrouping.kt`. */
sealed interface SearchRow {
    val type: MediaType
    val title: String
    val key: String

    data class WorkRow(val hit: SearchHit) : SearchRow {
        override val type: MediaType get() = MediaType.from(hit.contentType)
        override val title: String get() = hit.title
        override val key: String get() = "work:" + hit.workId
    }

    data class EpisodeRow(val hit: EpisodeHit) : SearchRow {
        override val type: MediaType get() = MediaType.from(hit.contentType ?: "series")
        override val title: String get() = hit.title
        override val key: String get() = "episode:" + hit.id
    }

    data class SourceRow(val source: FollowedSource) : SearchRow {
        override val type: MediaType get() = MediaType.from(source.type)
        override val title: String get() = source.title
        override val key: String get() = "source:" + source.id
    }
}

/** One per-type segment of the fan-out: what it holds and whether it has landed yet. */
sealed interface Segment {
    data object Pending : Segment
    data class Loaded(val rows: List<SearchRow>, val truncated: Boolean = false) : Segment
    data class Failed(val message: String) : Segment
}

/** A labelled section of grouped results, in display order. */
data class SearchSection(val type: MediaType, val title: String, val segment: Segment) {
    val rows: List<SearchRow> get() = (segment as? Segment.Loaded)?.rows.orEmpty()
}

/** The filter chips: All, then one per kind that can hold results. */
enum class SearchFilter(val label: String, val type: MediaType?) {
    ALL("All", null),
    MOVIES("Movies", MediaType.MOVIE),
    SERIES("Series", MediaType.SERIES),
    MUSIC("Music", MediaType.MUSIC),
    BOOKS("Books", MediaType.BOOK),
    PODCASTS("Podcasts & feeds", MediaType.PODCAST);

    fun admits(type: MediaType): Boolean = when (this) {
        ALL -> true
        PODCASTS -> type == MediaType.PODCAST || type == MediaType.FEED
        else -> type == this.type
    }
}

/**
 * Pure grouping over the fan-out's segments: sections in a fixed order, each typed and
 * themed by its media kind, with a segment that is still in flight shown as pending
 * rather than holding the whole list back. Unit-tested.
 */
object SearchGrouping {

    /** The section order for mixed results. */
    val ORDER: List<MediaType> = listOf(MediaType.MOVIE, MediaType.SERIES, MediaType.MUSIC, MediaType.BOOK, MediaType.PODCAST)

    /**
     * Build sections from per-type [segments] (keyed by the fan-out's kind), an
     * episodes segment folded into Series, and followed sources folded into Podcasts &
     * feeds. Rows whose own type differs from the segment they came from are re-homed so
     * a series work never sits under Movies.
     */
    fun group(
        segments: Map<MediaType, Segment>,
        episodes: Segment,
        sources: Segment,
        filter: SearchFilter = SearchFilter.ALL,
    ): List<SearchSection> {
        val buckets = LinkedHashMap<MediaType, MutableList<SearchRow>>()
        val pending = HashSet<MediaType>()
        val failed = HashMap<MediaType, String>()
        val truncated = HashSet<MediaType>()

        fun place(row: SearchRow) {
            val home = when (row.type) {
                MediaType.FEED, MediaType.PODCAST -> MediaType.PODCAST
                MediaType.AUDIOBOOK -> MediaType.BOOK
                MediaType.UNKNOWN -> MediaType.MOVIE
                else -> row.type
            }
            buckets.getOrPut(home) { ArrayList() }.add(row)
        }
        fun absorb(kind: MediaType, seg: Segment) {
            when (seg) {
                Segment.Pending -> pending.add(kind)
                is Segment.Failed -> failed[kind] = seg.message
                is Segment.Loaded -> {
                    if (seg.truncated) truncated.add(kind)
                    seg.rows.forEach(::place)
                }
            }
        }
        segments.forEach { (kind, seg) -> absorb(kind, seg) }
        absorb(MediaType.SERIES, episodes)
        absorb(MediaType.PODCAST, sources)

        val seen = HashSet<String>()
        return ORDER.filter { filter.admits(it) }.map { kind ->
            val rows = buckets[kind].orEmpty().filter { seen.add(it.key) }
            val segment: Segment = when {
                rows.isNotEmpty() -> Segment.Loaded(rows, kind in truncated)
                kind in pending -> Segment.Pending
                failed.containsKey(kind) -> Segment.Failed(failed.getValue(kind))
                else -> Segment.Loaded(emptyList())
            }
            SearchSection(kind, sectionTitle(kind), segment)
        }
    }

    fun sectionTitle(kind: MediaType): String = when (kind) {
        MediaType.PODCAST -> "Podcasts & feeds"
        else -> kind.plural
    }

    /** Every row in reading order. */
    fun flatten(sections: List<SearchSection>): List<SearchRow> = sections.flatMap { it.rows }

    /** True when every segment has landed (pending nowhere). */
    fun settled(sections: List<SearchSection>): Boolean = sections.none { it.segment is Segment.Pending }

    /** True when settled and nothing matched anywhere. */
    fun empty(sections: List<SearchSection>): Boolean = settled(sections) && sections.all { it.rows.isEmpty() }

    /** Client-side title match for followed sources (they are not searchable server-side). */
    fun matchSources(query: String, sources: List<FollowedSource>): List<SearchRow> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return sources.filter { it.title.lowercase().contains(q) || (it.feedRef?.lowercase()?.contains(q) ?: false) }
            .map { SearchRow.SourceRow(it) }
    }
}
