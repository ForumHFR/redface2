package fr.forumhfr.redface2.core.ui.motion

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * #249 §4 — `true` unless the user disabled animations system-wide (Developer options / "Remove
 * animations", or an accessibility profile setting `ANIMATOR_DURATION_SCALE` to 0). Reading the system
 * setting (rather than the app's unwired `future_a11y_reduce_motion` row) honours the OS-level
 * preference the same way Compose's own animations do. Defaults to enabled if the setting is absent.
 *
 * Moved from `core.ui.post` (#754 review): consumed by post shimmers, the pull loader AND the loading
 * skeletons — a shared motion concern, not a post-rendering one.
 */
@Composable
internal fun rememberAnimationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        val scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        scale != 0f
    }
}
