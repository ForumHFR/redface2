package fr.forumhfr.redface2.core.ui.editor

/** Availability of the two decisions for one offered draft. */
data class EditorDraftOfferDecisionState(
    val restoreEnabled: Boolean = true,
    val discardEnabled: Boolean = true,
)
