package fr.forumhfr.redface2.feature.messages

/**
 * #1040 lot 6 — raw scroll coordinates of one private-message page. They are the exact
 * `LazyListState.firstVisibleItemIndex` / `firstVisibleItemScrollOffset` primitives captured by the
 * screen after a user-driven scroll settles. The retained [PrivateMessageThreadViewModel] keeps one
 * value per VISITED page for the life of the authenticated display session only.
 *
 * This type is deliberately neither saveable nor persisted: unlike the topic reader, MP anchors do
 * not survive process death in this tranche. The list contains messages only (no lazy header item),
 * so [index] 0 is the first message card.
 */
data class PrivateMessageScrollAnchor(val index: Int, val offset: Int)

/**
 * One page/account/generation-scoped landing published atomically with the rendered MP content.
 * [CitedMessage] is an explicit user intention and therefore wins over an [Anchor] or [Top] chosen
 * for the same page. The ViewModel keeps the saved anchor in its map when that happens; only this
 * one-shot landing is acknowledged after the screen applied it.
 */
sealed interface PrivateMessagePageLanding {
    val generation: Int
    val account: String
    val page: Int

    data class CitedMessage(
        override val generation: Int,
        override val account: String,
        override val page: Int,
        val numreponse: Int,
    ) : PrivateMessagePageLanding

    data class Anchor(
        override val generation: Int,
        override val account: String,
        override val page: Int,
        val anchor: PrivateMessageScrollAnchor,
    ) : PrivateMessagePageLanding

    data class Top(
        override val generation: Int,
        override val account: String,
        override val page: Int,
    ) : PrivateMessagePageLanding
}
