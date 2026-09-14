package fr.forumhfr.redface2.core.domain.preferences

/**
 * #1388 — which Android system bars the fullscreen image viewer asks the window to hide.
 *
 * [anyHidden] is the single condition under which the viewer switches the window to
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`: as long as every bar is shown, the bars must stay
 * « real » (their insets are dispatched, so the viewer action bar sits ABOVE the navigation bar
 * instead of being overlaid by a transient one — the #1388 overlap report).
 */
data class ViewerBarsState(
    val hideStatusBar: Boolean,
    val hideNavigationBar: Boolean,
) {
    val anyHidden: Boolean get() = hideStatusBar || hideNavigationBar
}

/**
 * Pure policy mapping the #518 immersive setting and the viewer's own chrome visibility onto the
 * system bars to hide (#1388). Extracted next to [shouldRevealNavBar] so the decision is
 * unit-testable and lives in one place; the viewer applies it, nothing else reads it.
 *
 * Google Photos model, bounded by the user setting:
 * - the **status bar** follows the chrome alone: it is hidden only in the viewer's fullscreen mode,
 *   i.e. once a tap has hidden the Redface action bar (#1308). #518 deliberately never touches the
 *   status bar, so the setting is not consulted here.
 * - the **navigation bar** follows the chrome too, but stays hidden for the whole session when the
 *   immersive setting is on — app-wide consistency wins over chrome symmetry (arbitrated by XaTriX
 *   on #1388: immersive ON + chrome visible ⇒ navigation bar hidden).
 *
 * @param immersive the persisted #518 « hide the system navigation bar » setting
 *   ([UserPreferencesRepository.observeHideSystemNavBar]), off by default.
 * @param chromeVisible whether the viewer's own bottom action bar is currently shown.
 */
fun viewerSystemBars(immersive: Boolean, chromeVisible: Boolean): ViewerBarsState = ViewerBarsState(
    hideStatusBar = !chromeVisible,
    hideNavigationBar = immersive || !chromeVisible,
)
