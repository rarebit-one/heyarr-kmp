package one.rarebit.heyarr.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import one.rarebit.heyarr.core.auth.Credential
import one.rarebit.heyarr.core.net.HttpResponse
import one.rarebit.heyarr.core.net.HttpTransport
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.personalstate.FakeDeviceKey
import one.rarebit.heyarr.mobile.personalstate.FakeServer
import one.rarebit.heyarr.mobile.personalstate.IdentityCrypto
import one.rarebit.heyarr.mobile.personalstate.InMemorySpaceRegistry
import one.rarebit.heyarr.mobile.personalstate.PersonalStateClient
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator
import one.rarebit.heyarr.mobile.personalstate.SpaceSession
import one.rarebit.heyarr.mobile.playlist.PersonalActionsViewModel
import one.rarebit.heyarr.mobile.playlist.PlaylistsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The playlist/star ViewModels over the real engine + node fake (Unconfined so state settles inline). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistFlowTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun main() = Dispatchers.setMain(dispatcher)

    @After fun reset() = Dispatchers.resetMain()

    /** A transport that 404s everything — the library resolves no works, isolating the personal-state path. */
    private class NotFound : HttpTransport {
        override fun get(url: String, headers: Map<String, String>) = HttpResponse(404, "")
        override fun post(url: String, body: String?, contentType: String?, headers: Map<String, String>) = HttpResponse(404, "")
    }

    private fun coordinator(server: FakeServer): PersonalStateCoordinator {
        var space = 0
        var tag = 0
        val session = SpaceSession(
            client = PersonalStateClient(server, server.base, Credential.Session("t")),
            device = FakeDeviceKey(1),
            crypto = IdentityCrypto(),
            newSpaceId = { "space-${space++}" },
            newTag = { "tag${tag++}" },
        )
        return PersonalStateCoordinator(session, InMemorySpaceRegistry())
    }

    @Test
    fun notEnrolledWhenNoCoordinator() {
        val vm = PlaylistsViewModel(personalState = null, io = dispatcher)
        assertTrue(vm.state.value.notEnrolled)
        assertTrue(vm.state.value.playlists.isEmpty())
    }

    @Test
    fun createThenListPlaylists() {
        val vm = PlaylistsViewModel(coordinator(FakeServer()), io = dispatcher)
        assertTrue(vm.state.value.playlists.isEmpty())

        var created: String? = null
        vm.create("Focus") { created = it }
        assertEquals("space-0", created)

        val names = vm.state.value.playlists.map { it.name }
        assertEquals(listOf("Focus"), names)
    }

    @Test
    fun starToggleReflectsOptimisticallyAndReconciles() {
        val actions = PersonalActionsViewModel(coordinator(FakeServer()), LibraryClient(NotFound(), "https://n", Credential.Session("t")), io = dispatcher)
        assertTrue(actions.starredIds.value.isEmpty())

        actions.toggleStar("m1")
        assertTrue("m1" in actions.starredIds.value)

        actions.toggleStar("m1")
        assertTrue("m1" !in actions.starredIds.value)
    }

    @Test
    fun addToPlaylistFromACard() {
        val server = FakeServer()
        val coord = coordinator(server)
        val space = coord.createPlaylist("Mix")
        val actions = PersonalActionsViewModel(coord, LibraryClient(NotFound(), "https://n", Credential.Session("t")), io = dispatcher)

        actions.openAddToPlaylist("song-x")
        assertEquals("song-x", actions.addTarget.value)
        actions.addTargetTo(space)
        assertEquals(null, actions.addTarget.value)

        assertEquals(listOf("song-x"), coord.playlist(space)!!.itemIds)
    }

    @Test
    fun surfacesRoleSpaceIdsForTheGateway() {
        val coord = coordinator(FakeServer())
        assertNull(PlaylistsViewModel(coord, io = dispatcher).state.value.starredSpaceId)
        coord.setStarred("m1", true) // lazily creates the starred role space
        assertEquals("space-0", PlaylistsViewModel(coord, io = dispatcher).state.value.starredSpaceId)
    }
}
