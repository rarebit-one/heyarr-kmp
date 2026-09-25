package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.desktop.state.AppSession

/** A cover and where it came from. [external] is set only when a public source supplied it. */
data class Cover(val bitmap: ImageBitmap?, val external: ExternalMeta? = null)

/**
 * The one way every card and hero gets its picture: the node's own artwork first
 * (authenticated blob), else — when the preference allows — a cover from a public
 * keyless source for this kind of work, cached on disk. Lazy: nothing is fetched
 * until the card is composed.
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
            session.artwork.peek(it)
        },
    ),
    nodeArtPath,
    title,
    type,
    session.config.externalMetadata,
) {
    if (nodeArtPath != null) {
        val bmp = session.artwork.load(nodeArtPath)
        if (bmp != null) {
            value = Cover(bmp)
            return@produceState
        }
    }
    if (!session.config.externalMetadata || title.isBlank()) return@produceState
    val meta = session.external.lookup(MetaKey(type, title, year, creator, feedRef)) ?: return@produceState
    val url =
        (
            if (type == MediaType.SERIES ||
                type == MediaType.MOVIE
            ) {
                meta.landscapeImageUrl ?: meta.imageUrl
            } else {
                meta.imageUrl
            }
            )
            ?: run {
                value = Cover(null, meta)
                return@produceState
            }
    val bitmap = session.artwork.load(url) ?: meta.imageUrl?.takeIf { it != url }?.let { session.artwork.load(it) }
    value = Cover(bitmap, meta)
}
