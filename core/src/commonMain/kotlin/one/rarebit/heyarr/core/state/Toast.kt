package one.rarebit.heyarr.core.state

/** A typed toast: what happened, and — for a refusal — the tool and its rule text, verbatim. */
data class Toast(
    val id: Long,
    val kind: Kind,
    val title: String,
    val detail: String? = null,
    val tool: String? = null,
    // An optional single action rendered as a button on the card — e.g. "Cast anyway"
    // on a codec refusal. Dismissing or the timeout removes the toast either way.
    val action: ToastAction? = null,
) {
    enum class Kind { INFO, SUCCESS, ERROR, REFUSED }
}

/** A button on a toast: a short label and what it does. */
data class ToastAction(val label: String, val onClick: () -> Unit)
