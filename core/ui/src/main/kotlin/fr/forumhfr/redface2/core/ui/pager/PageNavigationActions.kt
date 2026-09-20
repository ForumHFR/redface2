package fr.forumhfr.redface2.core.ui.pager

/**
 * Chooses the shortcut contract [PageNavigation] renders above the direct page-jump field.
 *
 * Lives in its own file: detekt's `MatchingDeclarationName` requires a single top-level type to
 * carry the file's name, and `PageNavigation.kt` is named after its composable.
 */
enum class PageNavigationActions {
    /** Move one page backward or forward. This remains the recovery-safe default. */
    Adjacent,

    /** Jump directly to the first or last known page. */
    Extremes,
}
