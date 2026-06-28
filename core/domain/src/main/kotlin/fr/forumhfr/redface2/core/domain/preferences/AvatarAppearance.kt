package fr.forumhfr.redface2.core.domain.preferences

/**
 * GLOBAL appearance of the account avatar badge in the top bar (#718). Bundled so the badge — shown
 * on every main screen via [fr.forumhfr.redface2.core.ui.account.RedfaceAccountMenu], fed by a single
 * StateFlow — reads ONE value and never flickers between two independently-observed flows.
 *
 * - [border] — draw a thin outline around the round badge (default `false`, the borderless look
 *   shipped with the #603/#665 top-bar redesign). Purely decorative — not a contrast guarantee.
 * - [background] — what sits behind the avatar/initial (see [AvatarBackground]).
 *
 * NOT part of [FlagsViewSettings]: the avatar is global (every screen's top bar), so it must not
 * depend on a [fr.forumhfr.redface2.core.model.FlagType]-scoped resolution. The Drapeaux « Réglages
 * d'affichage » sheet is only an EDITING point; the source of truth is the global preference.
 */
data class AvatarAppearance(
    val border: Boolean = false,
    val background: AvatarBackground = AvatarBackground.Container,
)
