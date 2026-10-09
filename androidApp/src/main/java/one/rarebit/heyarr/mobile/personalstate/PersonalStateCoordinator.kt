package one.rarebit.heyarr.mobile.personalstate

/**
 * The app-facing façade over [SpaceSession] + [SpaceRegistry] — the one seam the
 * consumption UI talks to. It resolves the device-side role map (which space holds
 * the starred set / play history / reading positions) and treats every other
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

    /** Every openable non-role space, folded as a playlist (a space this device cannot decrypt is skipped). */
    fun playlists(): List<PlaylistView> {
        val roles = registry.roleSpaceIds()
        return session.listSpaces()
            .filter { it.id !in roles }
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

/** An existing space this device holds a copy of but cannot open now; it is never replaced. */
internal class SpaceUnreadableException(spaceId: String) :
    IllegalStateException("space $spaceId exists but this device cannot open it; not replacing it")
