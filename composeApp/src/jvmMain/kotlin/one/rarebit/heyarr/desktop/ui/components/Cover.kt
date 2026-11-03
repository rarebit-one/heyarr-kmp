package one.rarebit.heyarr.desktop.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import one.rarebit.heyarr.core.state.ExternalMeta
import one.rarebit.heyarr.core.state.MetaKey
import one.rarebit.heyarr.core.theme.MediaType
import one.rarebit.heyarr.ui.theme.CardAspect
import one.rarebit.heyarr.ui.theme.MediaThemes
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
            session.artwork.peek(it)
        },
    ),
    nodeArtPath,
    title,
    type,
    preferLandscape,
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
            if (preferLandscape) {
                meta.landscapeImageUrl ?: meta.imageUrl
            } else {
                meta.imageUrl ?: meta.landscapeImageUrl
            }
            )
            ?: run {
                value = Cover(null, meta)
                return@produceState
            }
    val bitmap = session.artwork.load(url) ?: meta.imageUrl?.takeIf { it != url }?.let { session.artwork.load(it) }
    value = Cover(bitmap, meta)
}
