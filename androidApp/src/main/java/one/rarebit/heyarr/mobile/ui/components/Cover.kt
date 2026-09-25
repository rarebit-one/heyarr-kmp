package one.rarebit.heyarr.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.mobile.heyarr.HeyarrApi
import one.rarebit.heyarr.mobile.state.AppSession

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
fun rememberCover(
    session: AppSession,
    type: MediaType,
    title: String,
    nodeArtPath: String?,
    year: Int? = null,
    creator: String? = null,
    feedRef: String? = null,
): State<Cover> = produceState(
    initialValue = Cover(
        nodeArtPath?.let {
            HeyarrApi.blobUrlFromPath(session.baseUrl, it)
        },
    ),
    nodeArtPath,
    title,
    type,
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
            if (type == MediaType.SERIES ||
                type == MediaType.MOVIE
            ) {
                meta.landscapeImageUrl ?: meta.imageUrl
            } else {
                meta.imageUrl
            },
            meta,
        )
}
