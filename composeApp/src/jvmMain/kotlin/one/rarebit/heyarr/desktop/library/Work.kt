package one.rarebit.heyarr.desktop.library

import one.rarebit.heyarr.core.library.CatalogWork

/**
 * A library entry as browsed from heyarr's native resources API (`GET /api/v1/works`,
 * `GET /api/v1/works/{id}` — heyarr-core `Work`). A thin projection of the far richer
 * server model, copied from heyarr-mobile's `library.Work` (trimmed to the fields this
 * desktop browse slice renders); the work's assets/wants load separately in a later
 * detail screen.
 */
data class Work(
    override val id: String,
    override val title: String,
    override val kind: String? = null,
    val artist: String? = null,
    val author: String? = null,
    val year: Int? = null,
    /** heyarr's normalised identity (`work_key`), so a rescan converges on the same work. */
    val workKey: String? = null,
    val sortTitle: String? = null,
    /** RFC 3339 server timestamps, as sent; parsed only for ordering/display. */
    val createdAt: String? = null,
    val updatedAt: String? = null,
    /** The `artwork` embed's content path when the listing was asked for it (`include=artwork`). */
    val artworkPath: String? = null,
) : CatalogWork {
    /** The timestamp "recent first" orders on: last touched, else created. */
    val recency: String? get() = updatedAt ?: createdAt

    /** A one-line subtitle for the browse row (creator / year / kind), when known. */
    val subtitle: String
        get() = listOfNotNull(
            artist ?: author,
            year?.toString(),
            kind,
        ).joinToString(" · ")
}
