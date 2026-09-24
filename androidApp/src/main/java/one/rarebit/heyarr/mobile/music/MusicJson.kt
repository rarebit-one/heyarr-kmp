package one.rarebit.heyarr.mobile.music

import one.rarebit.heyarr.core.net.JsonScan
import one.rarebit.heyarr.mobile.library.Work

object MusicJson {

    /** `GET /artists` rows: `{ name, work_count, artwork }`. */
    fun parseArtists(body: String): List<Artist> = JsonScan.objectsOf(body, listOf("items")).mapNotNull { obj ->
        val name = JsonScan.stringField(obj, "name") ?: return@mapNotNull null
        Artist(
            name = name,
            workCount = JsonScan.intField(obj, "work_count") ?: 0,
            artworkPath = JsonScan.objectAt(obj, "artwork")?.let { JsonScan.stringField(it, "content_url") },
        )
    }

    /**
     * The client-side sibling of `GET /artists`: group music works on `attributes.artist`,
     * count, and take the first album's poster (by year, then title) as the picture.
     * Works with no artist land under [UNKNOWN]. Sorted by name, case-insensitively. Pure.
     */
    fun groupByArtist(works: List<Work>): List<Artist> =
        works.groupBy { it.artist?.takeIf { a -> a.isNotBlank() } ?: UNKNOWN }
            .map { (name, albums) ->
                val first = albums.sortedWith(compareBy({ it.year ?: Int.MAX_VALUE }, { it.sortTitle ?: it.title.lowercase() }, { it.id }))
                    .firstOrNull { it.artworkPath != null } ?: albums.first()
                Artist(name = name, workCount = albums.size, artworkPath = first.artworkPath, artworkWorkId = first.id)
            }
            .sortedWith(compareBy({ it.name == UNKNOWN }, { it.name.lowercase() }, { it.name }))

    const val UNKNOWN = "Unknown artist"
}
