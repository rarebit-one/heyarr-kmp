package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.state.LandscapeArtwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LandscapeArtworkTest {
    private fun image(type: String, width: Int, height: Int, url: String) =
        """{"type":"$type","resolutions":{"original":{"width":$width,"height":$height,"url":"$url"}}}"""

    @Test fun prefersCorrectShapeThenResolution() {
        val images = listOf(
            image("poster", 4000, 6000, "https://art/poster"),
            image("banner", 3840, 2160, "https://art/banner"),
            image("background", 4000, 2500, "https://art/wrong-ratio"),
            image("background", 1920, 1080, "https://art/hd"),
            image("background", 3840, 2160, "https://art/4k"),
        )
        assertEquals("https://art/4k", LandscapeArtwork.tvmaze(images.joinToString(",", "[", "]")))
    }

    @Test fun missingOrUnsuitableBackgroundLeavesPosterFallbackAvailable() {
        for (body in listOf("[]", "{}", "garbage", "[{}]", "[" + image("background", 1920, 0, "https://art/broken") + "]", "[" + image("background", 300, 169, "https://art/tiny") + "]", "[" + image("poster", 1000, 1500, "https://art/poster") + "]")) {
            assertNull(LandscapeArtwork.tvmaze(body), body)
        }
    }
}
