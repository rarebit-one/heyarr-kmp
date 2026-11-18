package one.rarebit.heyarr.mobile.personalstate

import one.rarebit.heyarr.core.playback.PlaybackPreference

/**
 * The app-facing façade over [SpaceSession] + [SpaceRegistry] — the one seam the
 * consumption UI talks to. It resolves the device-side role map (which space holds
 * the starred set / play history / reading positions / synced preferences) and treats every other
 * openable space as a playlist, exactly as heyarr-core's device gateway does, so the
 * phone and the Mac gateway agree on what a space is. Role spaces are created lazily
 * on first write (ensure*), so a fresh device needs no setup step.
 *
 * Calls are blocking (they drive [PersonalStateClient] over HTTP); ViewModels run
 * them on a background dispatcher. Nothing here decrypts — that is [SpaceSession] on
 * the device; the node only ever sees ciphertext (Invariant 6).
 */
internal class PersonalStateCoordinator(private val session: SpaceSession, private val registry: SpaceRegistry) {
    data class PlaylistView(val spaceId: String, val name: String, val itemIds: List<String>)

    // --- playlists ----------------------------------------------------------------

    /**
     * Every openable non-role space, folded as a playlist (a space this device cannot decrypt is
     * skipped). A preferences space is a role even when this device never recorded it: another
     * device's, or one that lost the lowest-id race ([preferencesSpace]).
     */
    fun playlists(): List<PlaylistView> {
        val roles = registry.roleSpaceIds()
        val listed = session.listSpaces()
        val preferences = preferenceSpaces(listed, roles).toSet()
        return listed
            .filter { it.id !in roles && it.id !in preferences }
            .mapNotNull { info ->
                session.playlist(info.id)?.let { PlaylistView(info.id, registry.displayName(info.id), it.ids()) }
            }
    }

    fun playlist(spaceId: String): PlaylistView? =
        session.playlist(spaceId)?.let { PlaylistView(spaceId, registry.displayName(spaceId), it.ids()) }

    /** Mint a new playlist space (wrapped for this device + recovery), with an optional local name. */
    fun createPlaylist(name: String?): String {
        val id = session.createSpace("shared")
        if (!name.isNullOrBlank()) registry.setPlaylistName(id, name)
        return id
    }

    fun renamePlaylist(spaceId: String, name: String) = registry.setPlaylistName(spaceId, name)

    fun addToPlaylist(spaceId: String, itemId: String): PlaylistView? =
        session.addToPlaylist(spaceId, itemId)?.let { PlaylistView(spaceId, registry.displayName(spaceId), it.ids()) }

    fun removeFromPlaylist(spaceId: String, itemId: String): PlaylistView? =
        session.removeFromPlaylist(spaceId, itemId)?.let {
            PlaylistView(spaceId, registry.displayName(spaceId), it.ids())
        }

    // --- starred ------------------------------------------------------------------

    fun starredIds(): List<String> = registry.starredSpace()?.let { session.starred(it)?.ids() } ?: emptyList()

    /** Star or unstar an item, creating the starred role space on first use. Returns the new starred ids. */
    fun setStarred(itemId: String, starred: Boolean): List<String> {
        val space = ensure(registry::starredSpace, registry::setStarredSpace)
        val set = if (starred) session.star(space, itemId) else session.unstar(space, itemId)
        return set?.ids() ?: emptyList()
    }

    // --- play history -------------------------------------------------------------

    fun recordPlay(itemId: String) {
        session.recordPlay(ensure(registry::historySpace, registry::setHistorySpace), itemId)
    }

    /** Distinct items most-recently-played first (feeds a "recently played" row). */
    fun recentlyPlayedIds(): List<String> =
        registry.historySpace()?.let { session.history(it)?.recentIds() } ?: emptyList()

    // --- reading positions --------------------------------------------------------

    /** The exact locator this device last recorded for a publication, if any. */
    fun readingPosition(pubId: String): String? =
        registry.readingSpace()?.let { session.readingPositions(it)?.position(pubId) }

    fun setReadingPosition(pubId: String, position: String) {
        session.setReadingPosition(ensure(registry::readingSpace, registry::setReadingSpace), pubId, position)
    }

    // --- the person's synced preferences (heyarr-core#887) -------------------------

    /**
     * The person's playback preference as their devices last left it, or null when none has
     * been set (or this device can see no preferences space). The value is a last-writer-wins
     * register: the reading-position lattice under [PLAYBACK_KEY] (heyarr-core ADR-0123's
     * addendum), so a change on the television wins over an older one on the phone.
     */
    fun playbackPreference(): PlaybackPreference? {
        val space = preferencesSpace() ?: return null
        // A space this device has a copy of but cannot open right now (its keys answered 500, say)
        // is a failed read, never "no preference": the caller keeps what it had.
        val positions = session.readingPositions(space)
            ?: if (session.openState(space) ==
                SpaceSession.OpenState.NO_COPY
            ) {
                null
            } else {
                throw SpaceUnreadableException(space)
            }
        return positions?.position(PLAYBACK_KEY)?.let { PlaybackPreference.parse(it) }
    }

    /** Set the person's playback preference for all their devices, creating the space on first use. */
    fun setPlaybackPreference(preference: PlaybackPreference) {
        preferencesSpace() // adopt another device's space before minting one of our own
        val space = ensure(registry::preferencesSpace, registry::setPreferencesSpace)
        session.setReadingPosition(space, PLAYBACK_KEY, preference.planJson())
    }

    /**
     * The preferences space, found by what it holds and re-checked on every read. Role spaces
     * are recorded per device, so a phone signing in beside a television finds the
     * television's space as a space it can open whose register holds [PLAYBACK_KEY]. The
     * lowest such id wins and is recorded, even over one this device recorded earlier, so two
     * devices that each minted one before seeing the other settle on the same space, and on
     * its value: the losing space's last write is set aside, not merged, because two spaces'
     * Lamport clocks do not compare. The record only ever moves to a lower id, so a winning
     * space that fails to open for a moment is never traded for a loser, unless this device
     * holds no copy of it at all. A listing that fails keeps the recorded space.
     */
    private fun preferencesSpace(): String? {
        val recorded = registry.preferencesSpace()
        val others = registry.roleSpaceIds() - setOfNotNull(recorded)
        val found = runCatching { session.listSpaces() }.getOrNull()?.let { preferenceSpaces(it, others).firstOrNull() }
        return found?.takeIf { supersedes(it, recorded) }?.also(registry::setPreferencesSpace) ?: recorded
    }

    /** Whether [found] should replace the [recorded] preferences space: see [preferencesSpace]. */
    private fun supersedes(found: String, recorded: String?): Boolean = when {
        recorded == null || found < recorded -> true
        found == recorded -> false
        else -> session.openState(recorded) == SpaceSession.OpenState.NO_COPY
    }

    /** The [listed] spaces outside [exclude] whose register holds a playback preference, lowest id first. */
    private fun preferenceSpaces(listed: List<SpaceInfo>, exclude: Set<String>): List<String> = listed
        .filter { it.kind == "personal" } // role spaces; a playlist is "shared"
        .map { it.id }
        .filter { it !in exclude }
        .sorted()
        .filter { id ->
            runCatching { session.readingPositions(id)?.position(PLAYBACK_KEY) }.getOrNull()
                ?.let { PlaybackPreference.parse(it) } != null
        }

    // --- role space ids (for pointing the Mac gateway) ----------------------------

    fun starredSpaceId(): String? = registry.starredSpace()
    fun historySpaceId(): String? = registry.historySpace()

    /**
     * The role space, created on first use. Only a space this device holds NO copy of is replaced
     * (never shared with it, or gone). One it has a copy of but cannot open right now — a key
     * history inconsistent even after a retry, a node failure — is an error, not a reason to mint
     * an empty replacement: that would orphan the user's state behind a fresh space (ADR-0103).
     */
    private fun ensure(get: () -> String?, set: (String) -> Unit): String {
        val existing = get()
        if (existing != null) {
            when (session.openState(existing)) {
                SpaceSession.OpenState.OPEN -> return existing
                SpaceSession.OpenState.UNREADABLE -> throw SpaceUnreadableException(existing)
                SpaceSession.OpenState.NO_COPY -> Unit // mint a replacement below
            }
        }
        val id = session.createSpace("personal")
        set(id)
        return id
    }
}

/** The register key the playback preference is stored under in the preferences space. */
internal const val PLAYBACK_KEY = "playback.quality"

/** An existing space this device holds a copy of but cannot open now; it is never replaced. */
internal class SpaceUnreadableException(spaceId: String) :
    IllegalStateException("space $spaceId exists but this device cannot open it; not replacing it")
