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
internal class PersonalStateCoordinator(
    private val session: SpaceSession,
    private val registry: SpaceRegistry,
) {
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
        session.removeFromPlaylist(spaceId, itemId)?.let { PlaylistView(spaceId, registry.displayName(spaceId), it.ids()) }

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

    private fun ensure(get: () -> String?, set: (String) -> Unit): String {
        val existing = get()
        if (existing != null && session.canOpen(existing)) return existing
        val id = session.createSpace("personal")
        set(id)
        return id
    }
}
