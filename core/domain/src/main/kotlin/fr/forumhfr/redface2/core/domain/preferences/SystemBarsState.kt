package fr.forumhfr.redface2.core.domain.preferences

/**
 * #1388 — the complete state of the Android system bars of the app window RIGHT NOW: what is hidden,
 * and what the bars that remain VISIBLE must look like. There is exactly one writer of that window
 * (the app shell), so there is exactly one state, computed by [appSystemBars].
 *
 * [anyHidden] is the single condition under which the window switches to
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`; while nothing is hidden the behaviour is
 * `BEHAVIOR_DEFAULT`, so the bars stay « real » — Android dispatches their insets and the image
 * viewer's action bar sits ABOVE the navigation bar instead of being overlaid by a transient bar
 * (the #1388 overlap report).
 *
 * @property lightSystemBarIcons whether the clock, the status icons and the navigation glyphs must be
 *   drawn LIGHT (white-on-dark). It maps to the NEGATION of `isAppearanceLightStatusBars` /
 *   `isAppearanceLightNavigationBars`, which describe the BACKGROUND behind them.
 */
data class SystemBarsState(
    val hideStatusBar: Boolean,
    val hideNavigationBar: Boolean,
    val lightSystemBarIcons: Boolean,
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
 *   [navBarRevealed] is the #518 follow-up scroll-driven reveal. It belongs to the READING screens:
 *   it can only bring an immersive bar back there, and it is IGNORED for as long as [viewerActive]
 *   is true — « immersive ⇒ navigation bar hidden » holds in the viewer without exception, so a
 *   topic left with a revealed bar cannot leak a visible bar into the viewer's first frame. The
 *   reading facts come back on their own once the viewer is gone (the topic screen re-reports them).
 * - the **icon contrast** of whatever stays visible follows the surface DRAWN BEHIND the bars, which
 *   is the app theme everywhere except in the viewer: it paints a black backdrop, so light icons are
 *   the only legible choice there (#1388 — with the bars now visible over the viewer, a light theme
 *   used to put black glyphs and a black clock on black). It is an output of THIS policy, applied by
 *   the same single writer, so leaving the viewer recomputes the theme contrast instead of restoring
 *   a snapshot of it.
 *
 * With [viewerActive] `false` the result is exactly the historical #518 + #286 behaviour:
 * `hideNavigationBar == immersive && !navBarRevealed`, status bar untouched, icon contrast from the
 * effective app theme.
 *
 * @param immersive the persisted #518 « hide the system navigation bar » setting
 *   ([UserPreferencesRepository.observeHideSystemNavBar]), off by default.
 * @param navBarRevealed the scroll-driven reveal decision ([shouldRevealNavBar]) of the active topic.
 *   Ignored while [viewerActive] is true.
 * @param viewerActive whether the fullscreen image viewer is composed (an overlay or a modal route
 *   ABOVE it does not end that: the viewer is still on screen and keeps its window state).
 * @param chromeVisible the viewer's own bottom action bar; meaningless while [viewerActive] is false.
 * @param darkTheme the EFFECTIVE app theme (#286: the forced LIGHT/DARK preference, not the OS night
 *   mode), which decides the icon contrast everywhere the viewer's black backdrop is not shown.
 */
fun appSystemBars(
    immersive: Boolean,
    navBarRevealed: Boolean,
    viewerActive: Boolean,
    chromeVisible: Boolean,
    darkTheme: Boolean,
): SystemBarsState {
    val viewerFullscreen = viewerActive && !chromeVisible
    return SystemBarsState(
        hideStatusBar = viewerFullscreen,
        hideNavigationBar = viewerFullscreen || (immersive && (viewerActive || !navBarRevealed)),
        lightSystemBarIcons = viewerActive || darkTheme,
    )
}
