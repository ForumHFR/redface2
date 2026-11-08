package fr.forumhfr.redface2.core.ui.editor

import android.app.Activity
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.WindowMetricsCalculator

/** P20: full current ACTIVITY window, with the vertical union of stable system bars and cutout. */
data class EditorWindowMetrics(val windowHeight: Dp, val stableTop: Dp, val stableBottom: Dp) {
    init {
        require(listOf(windowHeight, stableTop, stableBottom).all { it.value.isFinite() && it >= 0.dp })
    }

    val usableHeight: Dp get() = (windowHeight - stableTop - stableBottom).coerceAtLeast(0.dp)
    val compactHeight: Boolean get() = usableHeight < 480.dp
}

/**
 * No content/decor height, screenHeightDp, maximum display metrics or IME inset enters this read.
 * Null until root insets exist: the host must wait rather than silently classify from configuration.
 * Device proof (API 29 adjustResize, 34/35, real multi-window) remains part of R1a-1's P20 gate.
 */
fun readEditorWindowMetrics(activity: Activity): EditorWindowMetrics? {
    val insets = ViewCompat.getRootWindowInsets(activity.window.decorView) ?: return null
    val stable = insets.getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
    )
    val bounds = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
    val density = activity.resources.displayMetrics.density
    return EditorWindowMetrics((bounds.height() / density).dp, (stable.top / density).dp,
        (stable.bottom / density).dp)
}

/** Refresh on rotation/configuration and decor layout, without replacing the host's inset listener. */
@Composable
fun rememberEditorWindowMetrics(activity: Activity): State<EditorWindowMetrics?> {
    val configuration = LocalConfiguration.current
    val metrics = remember(activity, configuration) { mutableStateOf(readEditorWindowMetrics(activity)) }
    DisposableEffect(activity, configuration) {
        val view = activity.window.decorView
        val listener = ViewTreeObserver.OnGlobalLayoutListener { metrics.value = readEditorWindowMetrics(activity) }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        metrics.value = readEditorWindowMetrics(activity)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return metrics
}

/** ED-04: future column applies this ONCE, before supplying measured available widths. */
@Composable
fun editorHorizontalInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

/** ED-04: future action-bar owner applies this ONCE; IME and navigation are never added together. */
@Composable
fun editorBottomInsets(): WindowInsets =
    WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
