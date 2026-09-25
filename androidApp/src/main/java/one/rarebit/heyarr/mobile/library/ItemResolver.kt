package one.rarebit.heyarr.mobile.library

import one.rarebit.heyarr.mobile.personalstate.ItemKind
import one.rarebit.heyarr.mobile.personalstate.ItemRef

/**
 * A resolved playlist / starred / history entry: the opaque CRDT [itemId] it came from
 * (kept so the reader can remove or re-star exactly that entry), the [work] it belongs
 * to, and — when the entry tagged a single file — the [asset] within that work.
 */
internal data class ResolvedItem(val itemId: String, val work: Work, val asset: WorkAsset?)

/**
 * Turns an opaque CRDT entry id into something the Playlist / Starred / Recently
 * readers can display (issue #41, Option B). It branches on the [ItemRef] kind:
 *
 *  - `work`  → `GET /works/{id}` (the historical path, unchanged).
 *  - `asset` → resolve the file to its work: `GET /assets/{id}` → its `edition_id` →
 *    `GET /editions/{id}` → its `work_id` → `GET /works/{id}`, carrying the [WorkAsset]
 *    back so a caller can show the track title and play the file itself.
 *  - `item`  → the same asset→work resolution (an episode / feed item is a file too),
 *    falling back to a work lookup if the id turns out to be a work after all.
 *
 * A legacy untagged id decodes as a work, so nothing stored before this change breaks.
 * Any read that fails (a 404, a deleted file, a work this device cannot see) resolves
 * to `null`; the readers already drop a null rather than paint a broken row.
 */
internal class ItemResolver(private val library: LibraryClient) {

    fun resolve(itemId: String): ResolvedItem? {
        val ref = ItemRef.decode(itemId)
        return when (ref.kind) {
            ItemKind.WORK -> library.getWork(ref.id)?.let { ResolvedItem(itemId, it, null) }

            ItemKind.ASSET -> resolveAsset(itemId, ref.id)

            ItemKind.ITEM -> resolveAsset(itemId, ref.id)
                ?: library.getWork(ref.id)?.let { ResolvedItem(itemId, it, null) }
        }
    }

    private fun resolveAsset(itemId: String, assetId: String): ResolvedItem? {
        val asset = library.getAsset(assetId) ?: return null
        val work = library.getEdition(asset.editionId)?.let { library.getWork(it.workId) } ?: return null
        return ResolvedItem(itemId, work, asset)
    }
}
