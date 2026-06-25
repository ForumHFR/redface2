package fr.forumhfr.redface2.feature.flags

/** Callbacks of the long-press [FlagActionsSheet] (copy-link / open-in-browser are handled inside). */
data class FlagSheetActions(
    val onOpen: () -> Unit,
    val onToggleSuperFavorite: () -> Unit,
    val onRemove: () -> Unit,
    val onDismiss: () -> Unit,
)
