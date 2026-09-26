package one.rarebit.heyarr.mobile.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.mobile.library.ItemResolver
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator

/**
 * The cross-screen personal-state actions — star/unstar and add to a playlist — plus
 * the derived row Home shows (starred), all decrypted
 * on this device. It is the one place a ★ or an "Add to playlist" tap on any card,
 * track or work goes, so those affordances share one optimistic path and one refresh.
 *
 * Null [personalState] (a device with no key) leaves every list empty and every write
 * a silent no-op — the honest degraded state, never a crash or a fake star.
 */
internal class PersonalActionsViewModel(
    private val personalState: PersonalStateCoordinator?,
    private val library: LibraryClient,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    /** Resolves a (possibly tagged) entry id to its work — asset/item ids fold to their work for these rows. */
    private val resolver = ItemResolver(library)

    private val _starredIds = MutableStateFlow<Set<String>>(emptySet())
    val starredIds: StateFlow<Set<String>> = _starredIds.asStateFlow()

    private val _starredWorks = MutableStateFlow<List<Work>>(emptyList())
    val starredWorks: StateFlow<List<Work>> = _starredWorks.asStateFlow()

    private val _playlists = MutableStateFlow<List<PersonalStateCoordinator.PlaylistView>>(emptyList())
    val playlists: StateFlow<List<PersonalStateCoordinator.PlaylistView>> = _playlists.asStateFlow()

    /** Which work an "Add to playlist" sheet is open for, or null. */
    private val _addTarget = MutableStateFlow<String?>(null)
    val addTarget: StateFlow<String?> = _addTarget.asStateFlow()

    val enabled: Boolean get() = personalState != null

    init {
        if (personalState != null) refresh()
    }

    fun refresh() {
        val ps = personalState ?: return
        viewModelScope.launch {
            withContext(io) {
                val ids = runCatching { ps.starredIds() }.getOrDefault(emptyList())
                // starredIds keeps the RAW (possibly tagged) entry ids: a track row's ★
                // state checks `ItemRef.asset(id).encode() in starredIds`, a work card's `w.id`.
                _starredIds.value = ids.toSet()
                _starredWorks.value = ids.mapNotNull { runCatching { resolver.resolve(it)?.work }.getOrNull() }
                    .distinctBy { it.id }
                _playlists.value = runCatching { ps.playlists() }.getOrDefault(emptyList())
            }
        }
    }

    fun toggleStar(itemId: String) {
        val ps = personalState ?: return
        val nowStarred = itemId !in _starredIds.value
        // Optimistic: reflect the toggle immediately, reconcile on the write's result.
        _starredIds.value = if (nowStarred) _starredIds.value + itemId else _starredIds.value - itemId
        viewModelScope.launch {
            val ids = withContext(io) { runCatching { ps.setStarred(itemId, nowStarred) }.getOrNull() }
            if (ids != null) _starredIds.value = ids.toSet() else refresh()
            refresh()
        }
    }

    fun openAddToPlaylist(itemId: String) {
        _addTarget.value = itemId
    }

    fun dismissAddToPlaylist() {
        _addTarget.value = null
    }

    fun addTargetTo(spaceId: String) {
        val ps = personalState ?: return
        val item = _addTarget.value ?: return
        _addTarget.value = null
        viewModelScope.launch {
            withContext(io) { runCatching { ps.addToPlaylist(spaceId, item) } }
            refresh()
        }
    }

    fun createPlaylistWithTarget(name: String?) {
        val ps = personalState ?: return
        val item = _addTarget.value ?: return
        _addTarget.value = null
        viewModelScope.launch {
            withContext(io) {
                runCatching {
                    val id = ps.createPlaylist(name?.trim()?.takeIf { it.isNotEmpty() })
                    ps.addToPlaylist(id, item)
                }
            }
            refresh()
        }
    }
}
