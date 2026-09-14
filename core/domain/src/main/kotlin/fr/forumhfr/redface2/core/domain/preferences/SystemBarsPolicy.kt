package fr.forumhfr.redface2.core.domain.preferences

/**
 * #1388 — which Android system bars the app window hides RIGHT NOW. There is exactly one writer of
 * that window (the app shell), so there is exactly one state, computed by [appSystemBars].
 *
 * [anyHidden] is the single condition under which the window switches to
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; while nothing is hidden the behaviour is
 * `BEHAVIOR_DEFAULT`, so the bars stay « real » — Android dispatches their insets and the image
 * viewer's action bar sits ABOVE the navigation bar instead of being overlaid by a transient bar
 * (the #1388 overlap report).
 */
data class SystemBarsState(
    val hideStatusBar: Boolean,
    val hideNavigationBar: Boolean,
) {
    val anyHidden: Boolean get() = hideStatusBar || hideNavigationBar
}

/**
 * Pure policy for the whole window (#518 + #1388). Extracted next to [shouldRevealNavBar] so the
 * decision is unit-testable and lives in one place: the shell reads live facts and applies this,
 * the fullscreen image viewer only PUBLISHES its intent ([viewerActive] / [chromeVisible]) and
 * never touches the window itself.
 *
 * - the **status bar** is hidden only by the viewer's own fullscreen mode, i.e. while the viewer is
 *   up and a tap has hidden its action bar (#1308). #518 deliberately never touches the status bar,
 *   so [immersive] does not appear in that term.
 * - the **navigation bar** follows the viewer chrome the same way, and is additionally hidden for
 *   the whole session when [immersive] is on — app-wide consistency wins over chrome symmetry
 *   (arbitrated by XaTriX on #1388: immersive ON + chrome visible ⇒ navigation bar hidden).
 *   [navBarRevealed] is the #518 follow-up scroll-driven reveal; it can only bring an immersive bar
 *   BACK, never hide one, and it never overrides the viewer's explicit fullscreen.
 *
 * With [viewerActive] `false` the result is exactly the historical #518 behaviour:
 * `hideNavigationBar == immersive && !navBarRevealed`, status bar untouched.
 *
 * @param immersive the persisted #518 « hide the system navigation bar » setting
 *   ([UserPreferencesRepository.observeHideSystemNavBar]), off by default.
 * @param navBarRevealed the scroll-driven reveal decision ([shouldRevealNavBar]) of the active topic.
 * @param viewerActive whether the fullscreen image viewer is composed (an overlay or a modal route
 *   ABOVE it does not end that: the viewer is still on screen and keeps its window state).
 * @param chromeVisible the viewer's own bottom action bar; meaningless while [viewerActive] is false.
 */
fun appSystemBars(
    immersive: Boolean,
    navBarRevealed: Boolean,
    viewerActive: Boolean,
    chromeVisible: Boolean,
): SystemBarsState {
    val viewerFullscreen = viewerActive && !chromeVisible
    return SystemBarsState(
        hideStatusBar = viewerFullscreen,
        hideNavigationBar = viewerFullscreen || (immersive && !navBarRevealed),
    )
}
