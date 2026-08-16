package fr.forumhfr.redface2.core.model.write

/**
 * Coordinates HFR needs to locate one quoted post or private message.
 *
 * [page] and [ref] are retained even though the proven topic contract currently resolves a quote
 * from [numreponse] alone. Private-message quoting has only been observed with both values in the
 * quote URL, so consumers for that scope must preserve them and fail closed when [ref] is absent.
 */
data class QuoteLocator(
    val page: Int,
    val numreponse: Int,
    val ref: Int?,
)

/**
 * One transient quote selection: typed server coordinates plus the card snapshot captured when
 * the user selected it. The snapshot is UI-agnostic and may become stale if the source is edited;
 * materialisation still fetches fresh BBCode from HFR at submit time.
 *
 * Selection order is owned by the basket/list containing these values. This model is deliberately
 * not serializable: it travels through in-memory handoffs and is never embedded in a nav route.
 */
data class QuoteSelection(
    val locator: QuoteLocator,
    val author: String,
    val excerpt: String,
) {
    val numreponse: Int get() = locator.numreponse
}
