package fr.forumhfr.redface2.core.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import fr.forumhfr.redface2.core.domain.preferences.SystemBarsState
import fr.forumhfr.redface2.core.domain.preferences.appSystemBars

/**
 * #1388 — the fullscreen image viewer's INTENT about the window system bars. The viewer never writes
 * the window: it publishes what it wants here, and [SystemBarsOwnerEffect] — the single writer,
 * hosted by the app shell — folds that intent into the app-wide policy.
 *
 * One writer is the whole point. The previous design let the viewer own the window while it was the
 * active route and hand it back on dispose; the hand-back raced the shell (the top route flips at the
 * pop, the viewer disposes ~200 ms later at the end of the exit transition, so the VIEWER was the last
 * writer with a stale snapshot), an overlay pushed above the viewer silently took ownership back, and
 * an activity re-creation re-snapshotted a window the viewer itself had already changed. With the
 * intent, none of those states exist: whoever recomposes, the shell recomputes from live facts and is
 * by construction the last writer.
 *
 * [active] is driven by the viewer's composition, not by the navigation stack: a bottom sheet or a
 * modal route ABOVE the viewer leaves it composed and therefore still in charge of the window.
 *
 * Registrations are keyed by an opaque producer TOKEN rather than collapsed into one global pair.
 * Two viewers can overlap — `RedfaceNavHost` stacks image-viewer routes without a guard, and the
 * outgoing one stays composed for its ~200 ms exit transition — and a single pair let the older one
 * erase the newer one's intent on its way out. Each producer now withdraws only its own token, and
 * the MOST RECENTLY registered one (the topmost on screen) provides [chromeVisible].
 */
@Stable
internal class ViewerBarsIntent {

    private val registrations = mutableStateListOf<ViewerBarsRegistration>()

    /** Whether any fullscreen viewer is composed right now. */
    val active: Boolean get() = registrations.isNotEmpty()

    /** The topmost viewer's action-bar visibility; `true` (a no-op for the policy) when there is none. */
    val chromeVisible: Boolean get() = registrations.lastOrNull()?.chromeVisible ?: true

    /**
     * Registers [token] — or updates its value IN PLACE, so publishing a chrome change never
     * reorders producers — as wanting [chromeVisible].
     */
    fun publish(token: Any, chromeVisible: Boolean) {
        val registration = ViewerBarsRegistration(token, chromeVisible)
        val index = registrations.indexOfFirst { it.token === token }
        if (index >= 0) registrations[index] = registration else registrations += registration
    }

    /** Removes ONLY [token]; any other viewer still composed keeps its own intent. */
    fun withdraw(token: Any) {
        registrations.removeAll { it.token === token }
    }
}

/** One producer's published intent. [token] is compared by identity, never by value. */
private data class ViewerBarsRegistration(val token: Any, val chromeVisible: Boolean)

/**
 * Provided by `RedfaceTheme` so the viewer (a leaf of `:core:ui`) and the shell (`:app`) share one
 * instance without threading it through the navigation host. The default instance exists only for
 * previews and isolated hosts, where nobody reads it.
 */
internal val LocalViewerBarsIntent = staticCompositionLocalOf { ViewerBarsIntent() }

/**
 * #518 / #1388 — narrow seam over [WindowInsetsControllerCompat], and the ONLY thing in the app that
 * hides or shows a system bar. Injectable so the requested state is observable from JVM tests:
 * Robolectric 4.16.1 ships no `ShadowWindowInsetsController`.
 *
 * Deliberately one atomic mutator taking an absolute state: there is no « toggle » and no stored
 * snapshot anywhere, so a test asserts states in order, never a sequence of partial calls.
 */
internal interface SystemBarsController {
    /** Hides or shows each system bar per [bars] and sets [behavior]. */
    fun applyBars(bars: SystemBarsState, behavior: Int)
}

/** Production [SystemBarsController]: the real host window. */
internal class WindowSystemBarsController(
    private val window: Window,
    private val view: View,
) : SystemBarsController {

    override fun applyBars(bars: SystemBarsState, behavior: Int) {
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
 * Test seam: `null` (the default) makes [SystemBarsOwnerEffect] resolve the host Activity's window.
 * A test provides a recording double instead.
 */
internal val LocalSystemBarsController = staticCompositionLocalOf<SystemBarsController?> { null }

/**
 * #518 / #1388 — the single writer of the window system bars, hosted by the app shell.
 *
 * It combines the shell's own facts with the viewer's published intent through the pure
 * [appSystemBars], then applies the result. Because nothing else ever writes the window, every
 * transition ends with this effect: when the viewer disposes, its intent drops, this recomposes and
 * applies the shell state LAST — no snapshot to restore, no hand-back race. « The shell state » is
 * the CURRENT one, not the one from before the viewer opened: a scroll-driven reveal that was active
 * when the viewer opened is not replayed, the reading screen re-reports it on the next scroll.
 *
 * Swipe behaviour, tied to the policy: while nothing is hidden the window keeps `BEHAVIOR_DEFAULT`,
 * so the bars are real and dispatch their insets. As soon as a bar is hidden,
 * `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` lets a swipe from the edge bring the bars back
 * TRANSIENTLY (translucent, auto-hiding, no inset change so no layout jump); the definitive way back
 * is the viewer tap, or leaving immersive mode.
 *
 * Re-asserted on `ON_RESUME`: returning from another app, the recents screen, a share sheet or the
 * browser restores the bars without a recomposition.
 *
 * @param viewerRouteActive the shell's own « the viewer is the active destination » fact. It only
 *   ever ADDS to [ViewerBarsIntent.active] (never revokes it), so the very first frame after an
 *   activity re-creation already carries the viewer state, before the viewer has published anything.
 */
@Composable
fun SystemBarsOwnerEffect(
    immersive: Boolean,
    navBarRevealed: Boolean,
    viewerRouteActive: Boolean,
) {
    val controller = rememberSystemBarsController() ?: return
    val intent = LocalViewerBarsIntent.current
    val bars = appSystemBars(
        immersive = immersive,
        navBarRevealed = navBarRevealed,
        viewerActive = intent.active || viewerRouteActive,
        chromeVisible = intent.chromeVisible,
    )
    val behavior = if (bars.anyHidden) {
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    } else {
        WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    }
    DisposableEffect(controller, bars, behavior) {
        controller.applyBars(bars, behavior)
        onDispose {}
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { controller.applyBars(bars, behavior) }
}

/**
 * The injected double when a test provides one ([LocalSystemBarsController]), the host Activity's
 * window otherwise. `null` on hosts without an Activity (@Preview): nothing is then written.
 */
@Composable
private fun rememberSystemBarsController(): SystemBarsController? {
    val injected = LocalSystemBarsController.current
    val view = LocalView.current
    val window = view.context.findActivity()?.window?.takeUnless { view.isInEditMode }
    return remember(injected, window, view) {
        injected ?: window?.let { WindowSystemBarsController(it, view) }
    }
}

/**
 * Resolves the host Activity defensively instead of casting `view.context`: the shell is mounted
 * under MainActivity today, but a future ContextWrapper in the chain would make a hard cast crash.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
