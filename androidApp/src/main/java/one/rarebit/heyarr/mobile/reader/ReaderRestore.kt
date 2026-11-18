package one.rarebit.heyarr.mobile.reader

/**
 * What a [ReaderActivity] being created does with its book (heyarr-kmp#240). Pure, unit-tested.
 *
 * After process death Android brings back only the activity on top. When that is the reader,
 * the shell under it (MainActivity and its AppViewModel) is a record in the back stack and is
 * not created, and the shell is what makes this process signed in: it installs the live
 * credential into AppGraph.authHeader and adopts the device credential. A reader that opened
 * its book there fetched the blob with no credential, and the node refused it. On a phone that
 * read as "Could not fetch the file: …RetrieveUrlError\$Reading@…". So in that case the reader
 * steps aside and hands the book to the shell, which comes back from its own saved state, signs
 * in as it does at any start, and opens the book again exactly as a first open does, at the
 * page this phone kept for it.
 */
enum class ReaderStart {
    /** Open the book here: a first open, or a rotation inside a process whose shell is up. */
    OPEN,

    /** Hand the book to the shell and finish: Android started this process to bring the reader back. */
    HAND_TO_SHELL,
    ;

    companion object {
        /**
         * [restored] is whether Android handed the activity a saved state; [firstScreenInProcess]
         * is whether no activity of this app has been created in this process before it. Both
         * together mean the process died behind the reader and was started again to show it.
         * A rotation, or a test that opens the reader on its own and then recreates it, is
         * restored but not first, and opens in place.
         */
        fun of(restored: Boolean, firstScreenInProcess: Boolean): ReaderStart =
            if (restored && firstScreenInProcess) HAND_TO_SHELL else OPEN
    }
}

/** The book a reader was opened on: what its intent carries, so the shell can open it again. */
data class ReaderRequest(val assetId: String, val url: String, val title: String, val format: ReaderFormat?)
