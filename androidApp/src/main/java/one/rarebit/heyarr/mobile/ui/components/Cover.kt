package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.state.AppSession
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaThemes

/** A cover and where it came from. [external] is set only when a public source supplied it. */
data class Cover(val url: String?, val external: ExternalMeta? = null)

/**
 * The one way every card and hero gets its picture: the node's own artwork first
 * (the authenticated blob route — Coil carries the credential through the shared
 * client), else — when the preference allows — a cover from a public keyless source
 * for this kind of work, cached on disk for a week. Lazy: nothing is looked up until
 * the card is composed, and Coil fetches nothing until it is drawn.
 */
@Composable
@Suppress("LongParameterList") // the lookup key's parts plus the shape preference, all optional after the first four.
fun rememberCover(
    session: AppSession,
    type: MediaType,
    title: String,
    nodeArtPath: String?,
    year: Int? = null,
    creator: String? = null,
    feedRef: String? = null,
    /**
     * Whether a public fallback should prefer the wide (landscape) image over the portrait
     * one. Follows the card shape by default: a poster card wants the poster. The detail
     * hero passes true when it is drawn wide.
     */
    preferLandscape: Boolean = MediaThemes.of(type).aspect == CardAspect.WIDE,
): State<Cover> = produceState(
    initialValue = Cover(
        nodeArtPath?.let {
            HeyarrApi.blobUrlFromPath(session.baseUrl, it)
        },
    ),
    nodeArtPath,
    title,
    type,
    preferLandscape,
    session.externalMetadata,
) {
    if (nodeArtPath !=
        null
    ) {
        value = Cover(HeyarrApi.blobUrlFromPath(session.baseUrl, nodeArtPath))
        return@produceState
    }
    if (!session.externalMetadata || title.isBlank()) {
        value = Cover(null)
        return@produceState
    }
    val meta = session.external.lookup(MetaKey(type, title, year, creator, feedRef)) ?: return@produceState
    value =
        Cover(
            if (preferLandscape) {
                meta.landscapeImageUrl ?: meta.imageUrl
            } else {
                meta.imageUrl ?: meta.landscapeImageUrl
            },
            meta,
        )
}
