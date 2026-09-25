package one.rarebit.heyarr.desktop

import one.rarebit.heyarr.core.mcp.DiscoveryJson
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.MovieArtwork
import kotlin.test.*

class MovieArtworkTest {
    private val hits = DiscoveryJson.list(
        """{"results":[{"title":"Alien: Romulus","year":2024,"type":"movie","source":"tmdb","external_id":"945961","poster_url":"https://image.tmdb.org/t/p/w500/poster.jpg","backdrop_url":"https://image.tmdb.org/t/p/w1280/backdrop.jpg"}]}""",
    )

    @Test fun matchesPunctuationAndRetainsBothRoles() {
        val art = MovieArtwork.select(MetaKey(MediaType.MOVIE, "Alien Romulus", 2024), hits)!!
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", art.landscapeImageUrl)
        assertEquals("https://image.tmdb.org/t/p/w500/poster.jpg", art.imageUrl)
    }

    @Test fun refusesWrongYearTitleTypeAndAmbiguousRemakes() {
        assertNull(MovieArtwork.select(MetaKey(MediaType.MOVIE, "Alien Romulus", 1979), hits))
        assertNull(MovieArtwork.select(MetaKey(MediaType.MOVIE, "Alien", 2024), hits))
        assertNull(
            MovieArtwork.select(
                MetaKey(MediaType.MOVIE, "Alien Romulus", 2024),
                hits.map {
                    it.copy(type = "tv_series")
                },
            ),
        )
        assertNull(
            MovieArtwork.select(
                MetaKey(MediaType.MOVIE, "Alien Romulus"),
                hits + hits[0].copy(externalId = "other", year = 2025),
            ),
        )
    }
}
