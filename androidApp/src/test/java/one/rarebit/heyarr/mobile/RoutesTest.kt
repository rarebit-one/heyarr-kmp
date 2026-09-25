package one.rarebit.heyarr.mobile

import kotlinx.serialization.json.Json
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.nav.Route
import one.rarebit.heyarr.mobile.nav.detailRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Routes carry ids and display hints only, and round-trip through the serializer Navigation uses. */
class RoutesTest {

    @Test fun aDetailRouteCarriesTheKindByNameAndReadsItBack() {
        val r = detailRoute("w-1", MediaType.SERIES, "Yellowstone", from = "Home", curate = true)
        assertEquals("SERIES", r.type)
        assertEquals(MediaType.SERIES, r.typeHint)
        assertTrue(r.curate)
        val json = Json.encodeToString(Route.Detail.serializer(), r)
        assertEquals(r, Json.decodeFromString(Route.Detail.serializer(), json))
    }

    @Test fun anUnknownKindWordFallsBackToUnknown() {
        assertEquals(MediaType.UNKNOWN, Route.Detail("w", type = "whatever").typeHint)
        assertEquals(MediaType.UNKNOWN, Route.Detail("w").typeHint)
    }

    @Test fun onlyThePlayerOwnsTheWholeScreen() {
        assertTrue(Route.isFullScreen(Route.Player))
        assertFalse(Route.isFullScreen(Route.Home))
        assertFalse(Route.isFullScreen(Route.Detail("w")))
        assertFalse(Route.isFullScreen(null))
    }

    @Test fun playlistRoutesRoundTrip() {
        val p = Route.Playlist("space-1", "Road trip")
        assertEquals(
            p,
            Json.decodeFromString(Route.Playlist.serializer(), Json.encodeToString(Route.Playlist.serializer(), p)),
        )
    }
}
