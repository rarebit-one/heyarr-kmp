package one.rarebit.heyarr.mobile.cast

/**
 * Where a play goes while Google Cast is in the picture (heyarr-kmp#250). Pure, so the rule
 * that keeps two transcodes of one film apart is unit-tested rather than found on a TV.
 *
 * A second concurrent transcode of the same film — the local player still open while the
 * receiver played it — preceded the wedged receiver on real hardware (heyarr-kmp#248). So:
 *  - with a Cast session up, a video goes to the TV ([Route.CAST]), the way every Android
 *    app with a Cast button behaves; disconnecting from the Cast button plays here again;
 *  - while this phone still owns the receiver's film but the session is not up (the SDK is
 *    reconnecting after a network drop), that film may not start here ([Route.BLOCKED]):
 *    the caller says where it plays and offers to stop the cast;
 *  - music, podcasts and audiobooks never cast, and anything else plays here ([Route.LOCAL]).
 * A film put back paused after process death ([startPaused]) is never sent on its own: no
 * one pressed Play.
 */
object CastGate {
    enum class Route { LOCAL, CAST, BLOCKED }

    fun route(
        isVideo: Boolean,
        assetId: String?,
        startPaused: Boolean,
        sessionLive: Boolean,
        castingAsset: String?,
    ): Route = when {
        !isVideo -> Route.LOCAL
        castingAsset != null && assetId == castingAsset && !sessionLive -> Route.BLOCKED
        sessionLive && !startPaused && assetId != null -> Route.CAST
        castingAsset != null && assetId == castingAsset -> Route.BLOCKED
        else -> Route.LOCAL
    }
}
