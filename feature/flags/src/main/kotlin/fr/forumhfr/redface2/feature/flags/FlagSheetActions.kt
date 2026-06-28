package fr.forumhfr.redface2.feature.flags

/**
 * Callbacks of the long-press [FlagActionsSheet] (copy-link / open-in-browser / share are handled
 * inside the sheet via the platform Context).
 *
 * #676 v2 — [onOpen] now carries the target page so the sheet can offer distinct navigation actions
 * (resume at the last-read page, jump to the first unread page, open the last page) without leaking
 * those UI variants into the parent: the parent only needs to know which page to navigate to.
 */
data class FlagSheetActions(
    val onOpen: (page: Int) -> Unit,
    // #15 — open the reply editor for this topic (HFR appends at the end, so the host opens it on the
    // last page). « Aller à une page » reuses [onOpen] with the page picked in the in-sheet dialog.
    val onReply: () -> Unit,
    val onToggleSuperFavorite: () -> Unit,
    val onRemove: () -> Unit,
    val onDismiss: () -> Unit,
)
