package fr.forumhfr.redface2.core.ui.viewer

import android.view.View
import android.view.Window
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import fr.forumhfr.redface2.core.domain.preferences.ViewerBarsState

/**
 * #518 — the persisted « hide the Android system navigation bar » setting, provided by
 * `RedfaceTheme` at the app root and read by the image viewer to bound its own bar policy
 * ([fr.forumhfr.redface2.core.domain.preferences.viewerSystemBars]). Same shape and rationale as
 * `LocalAlwaysAskLinkApp`: a window-level preference the shell already collects, surfaced to a leaf
 * in `:core:ui` without threading it through the whole navigation host. `false` (the preference's
 * own default) keeps previews and isolated hosts on the non-immersive behaviour.
 */
val LocalHideSystemNavBar = staticCompositionLocalOf { false }

/** Bar visibility as the window reports it, used to snapshot the state to restore on exit. */
internal data class ViewerBarsVisibility(
    val statusBarVisible: Boolean,
    val navigationBarVisible: Boolean,
)

/**
 * #1388 — narrow seam over [WindowInsetsControllerCompat] so the viewer's system-bar decisions are
 * observable from JVM tests: Robolectric 4.16.1 ships no `ShadowWindowInsetsController`, so the
 * requested hide/show can only be asserted through an injected double
 * ([LocalViewerSystemBarsController]).
 *
 * Deliberately one atomic mutator: the viewer always decides both bars and the behaviour together,
 * so a test asserts a state, never a sequence of unrelated calls.
 */
internal interface ViewerSystemBarsController {

    /**
     * Visibility of both bars right now, or `null` when the window has no dispatched insets yet
     * (the first frame) — the caller must NOT turn that into a guess, see `ImageViewerScreen`.
     */
    fun currentVisibility(): ViewerBarsVisibility?

    /** The window's current `systemBarsBehavior`, restored verbatim when the viewer closes. */
    fun currentBehavior(): Int

    /** Hides or shows each system bar per [bars] and sets [behavior]. */
    fun applyBars(bars: ViewerBarsState, behavior: Int)
}

/**
 * Production [ViewerSystemBarsController]: the real window. Resolved fresh on each call rather than
 * cached, mirroring the existing call sites — `WindowCompat.getInsetsController` is a cheap wrapper
 * and the window's decor view can be re-created under the composable.
 */
internal class WindowViewerSystemBarsController(
    private val window: Window,
    private val view: View,
) : ViewerSystemBarsController {

    override fun currentVisibility(): ViewerBarsVisibility? {
        val insets = ViewCompat.getRootWindowInsets(view) ?: return null
        return ViewerBarsVisibility(
            statusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars()),
            navigationBarVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars()),
        )
    }

    override fun currentBehavior(): Int = WindowCompat.getInsetsController(window, view).systemBarsBehavior

    override fun applyBars(bars: ViewerBarsState, behavior: Int) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = behavior
        controller.setHidden(WindowInsetsCompat.Type.statusBars(), bars.hideStatusBar)
        controller.setHidden(WindowInsetsCompat.Type.navigationBars(), bars.hideNavigationBar)
    }

    private fun WindowInsetsControllerCompat.setHidden(type: Int, hidden: Boolean) {
        if (hidden) hide(type) else show(type)
    }
}

/**
 * Test seam: `null` (the default) makes the viewer resolve the host Activity's window. A test
 * provides a recording double instead, since no Robolectric shadow exposes the requested bar state.
 */
internal val LocalViewerSystemBarsController = staticCompositionLocalOf<ViewerSystemBarsController?> { null }
