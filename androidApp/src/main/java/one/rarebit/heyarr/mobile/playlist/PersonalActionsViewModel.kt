package one.rarebit.heyarr.mobile.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.rarebit.heyarr.core.playback.PlaybackPreference
import one.rarebit.heyarr.mobile.library.ItemResolver
import one.rarebit.heyarr.mobile.library.LibraryClient
import one.rarebit.heyarr.mobile.library.Work
import one.rarebit.heyarr.mobile.personalstate.PersonalStateCoordinator
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cross-screen personal-state actions — star/unstar, add to a playlist and the person's
 * synced playback preference (heyarr-core#887) — plus the derived row Home shows (starred), all decrypted
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

    /** The person's playback default as their devices last left it; null when none is set (or readable). */
    private val _accountPlayback = MutableStateFlow<PlaybackPreference?>(null)
    val accountPlayback: StateFlow<PlaybackPreference?> = _accountPlayback.asStateFlow()

    /**
     * One preference write at a time, in tap order: two concurrent writes fold the same register
     * and mint the same Lamport counter, and the tie would then go to a random writer id rather
     * than to the later tap.
     */
    private val playbackWrites = Mutex()

    /**
     * Writes not yet sent, and every write ever started. A read of the register shows its answer
     * only when no write is pending and none started while it was in flight. Otherwise an older
     * value read during a write would overwrite the newer tap shown optimistically.
     */
    private val pendingPlaybackWrites = AtomicInteger()
    private val playbackGeneration = AtomicInteger()

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
                readPlayback(ps)
            }
        }
    }

    /** Read the register and show it, unless a write overtook the read ([pendingPlaybackWrites]). */
    private fun readPlayback(ps: PersonalStateCoordinator) {
        val generation = playbackGeneration.get()
        // A failed read keeps what was shown: it is not "the person has no preference".
        runCatching { ps.playbackPreference() }.onSuccess {
            if (pendingPlaybackWrites.get() == 0 && playbackGeneration.get() == generation) _accountPlayback.value = it
        }
    }

    /**
     * Set the person's playback default for all their devices. Optimistic; writes are serialised
     * ([playbackWrites]), and the last one settles what is shown: re-read after it, so a write
     * that failed, or another device's later one, is what the phone plays by.
     */
    fun setAccountPlayback(preference: PlaybackPreference) {
        val ps = personalState ?: return
        playbackGeneration.incrementAndGet()
        pendingPlaybackWrites.incrementAndGet()
        _accountPlayback.value = preference
        viewModelScope.launch {
            playbackWrites.withLock {
                withContext(io) {
                    runCatching { ps.setPlaybackPreference(preference) }
                    // A later tap still queued behind this write settles what is shown instead.
                    if (pendingPlaybackWrites.decrementAndGet() == 0) readPlayback(ps)
                }
            }
        }
    }

    /**
     * Re-read the person's playback default: another device may have changed it since. The app
     * calls this whenever it comes back to the foreground, so a change made on the television
     * applies to the phone's next play.
     */
    fun refreshPlayback() {
        val ps = personalState ?: return
        viewModelScope.launch {
            withContext(io) { readPlayback(ps) }
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
