package one.rarebit.heyarr.desktop.state

import one.rarebit.heyarr.core.mcp.DiscoveryHit
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey

object MovieArtwork {
    fun select(key: MetaKey, hits: List<DiscoveryHit>): ExternalMeta? {
        fun title(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val hit =
            hits.filter {
                it.type == "movie" && it.source == "tmdb" && title(it.title) == title(key.title) &&
                    (key.year == null || key.year == it.year)
            }
                .distinctBy { it.externalId }.singleOrNull() ?: return null
        fun image(url: String?) = url?.takeIf { it.startsWith("https://image.tmdb.org/t/p/") }
        val backdrop = image(hit.backdropUrl) ?: return null
        return ExternalMeta(
            imageUrl = image(hit.posterUrl),
            landscapeImageUrl = backdrop,
            synopsis = hit.overview,
            source = "TMDB",
            sourceUrl = hit.externalId?.let {
                "https://www.themoviedb.org/movie/$it"
            },
        )
    }
}
