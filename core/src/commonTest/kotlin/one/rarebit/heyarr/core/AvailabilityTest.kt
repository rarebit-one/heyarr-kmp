package one.rarebit.heyarr.core

import one.rarebit.heyarr.core.auth.ClientMode
import one.rarebit.heyarr.core.heyarr.QualityProfile
import one.rarebit.heyarr.core.heyarr.QualityProfiles
import one.rarebit.heyarr.core.state.Availability
import one.rarebit.heyarr.core.state.LibraryStatus
import one.rarebit.heyarr.core.theme.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The consumer face of library status, and the profile a one-click "Get it" wants with. */
class AvailabilityTest {
    @Test
    fun heldBytesAreReadyWhateverTheWantSays() {
        assertEquals(Availability.READY, Availability.of(LibraryStatus.NOT_TRACKED, playable = true))
        assertEquals(Availability.READY, Availability.of(LibraryStatus.MISSING, playable = true))
        assertEquals(Availability.READY, Availability.of(LibraryStatus.IN_LIBRARY))
    }

    @Test
    fun wantsReadAsComingOrUnavailable() {
        assertEquals(Availability.ON_ITS_WAY, Availability.of(LibraryStatus.WANTED))
        assertEquals(Availability.NOT_AVAILABLE, Availability.of(LibraryStatus.MISSING))
        assertEquals(Availability.GETTABLE, Availability.of(LibraryStatus.NOT_TRACKED))
    }

    @Test
    fun aCataloguedWorkNoWantNamesWasScannedSoIsReady() {
        assertEquals(Availability.READY, Availability.ofCatalogued(LibraryStatus.NOT_TRACKED))
        assertEquals(Availability.ON_ITS_WAY, Availability.ofCatalogued(LibraryStatus.WANTED))
        assertEquals(Availability.NOT_AVAILABLE, Availability.ofCatalogued(LibraryStatus.MISSING))
    }

    @Test
    fun anUnknownStateIsNeverReady() {
        assertEquals(Availability.ON_ITS_WAY, Availability.of(LibraryStatus.ofState("SOMETHING_NEW")))
    }

    @Test
    fun getItIsOfferedOnlyForGettableWorksAndOnlyWhereWantingIsAllowed() {
        assertTrue(Availability.GETTABLE.offersGetIt(ClientMode.ENROLLED))
        assertFalse(Availability.GETTABLE.offersGetIt(ClientMode.GUEST))
        assertFalse(Availability.ON_ITS_WAY.offersGetIt(ClientMode.ENROLLED))
        assertFalse(Availability.READY.offersGetIt(ClientMode.ENROLLED))
    }

    private val everyday = QualityProfile("1", "everyday", null, listOf("movie", "series"))
    private val archival = QualityProfile("2", "archival", null, listOf("movie"))
    private val published = QualityProfile("3", "published", null, listOf("book"))

    @Test
    fun theDefaultProfilePrefersEverydayAmongThoseOffered() {
        val all = listOf(archival, everyday, published)
        assertEquals("everyday", QualityProfiles.defaultFor(MediaType.MOVIE, all))
        assertEquals("published", QualityProfiles.defaultFor(MediaType.BOOK, all))
    }

    @Test
    fun aKindNoProfileFitsHasNoDefault() {
        assertNull(QualityProfiles.defaultFor(MediaType.MUSIC, listOf(everyday, published)))
        assertEquals(listOf(everyday), QualityProfiles.offeredFor(MediaType.SERIES, listOf(everyday, published)))
    }
}
