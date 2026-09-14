package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.core.view.WindowInsetsControllerCompat
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.preferences.SystemBarsState

/**
 * #1388 — the doubles and the expected states shared by [SystemBarsOwnerTest] (one composition, many
 * transitions) and [SystemBarsOwnerRecreationTest] (a real Activity, re-created). Keeping ONE table of
 * expected states means the two tests cannot drift apart on what « the right window state » is.
 */
internal class RecordingSystemBarsController : SystemBarsController {

    /** Every window write, in order and WITHOUT de-duplication: a redundant write is a defect. */
    val applied = mutableListOf<AppliedBars>()

    override fun applyBars(bars: SystemBarsState, behavior: Int) {
        applied += AppliedBars(bars, behavior)
    }
}

internal data class AppliedBars(val bars: SystemBarsState, val behavior: Int)

private fun barsState(status: Boolean, navigation: Boolean, lightIcons: Boolean, behavior: Int) =
    AppliedBars(
        SystemBarsState(
            hideStatusBar = status,
            hideNavigationBar = navigation,
            lightSystemBarIcons = lightIcons,
        ),
        behavior,
    )

internal const val SOURCE_A = "https://images.example.org/bars-a.jpg"
internal const val SOURCE_B = "https://images.example.org/bars-b.jpg"

private val DEFAULT_BEHAVIOR = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
private val TRANSIENT = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

/** Both bars up, icons from a LIGHT app theme: the app outside the viewer, immersive mode off. */
internal val VISIBLE_DARK_ICONS = barsState(false, navigation = false, lightIcons = false, DEFAULT_BEHAVIOR)

/** Both bars up with light icons: a dark app theme, or the viewer's black backdrop under them. */
internal val VISIBLE_LIGHT_ICONS = barsState(false, navigation = false, lightIcons = true, DEFAULT_BEHAVIOR)

/** #518 immersive mode on a reading screen of a light-themed app. */
internal val NAV_HIDDEN_DARK_ICONS = barsState(false, navigation = true, lightIcons = false, TRANSIENT)

/** #518 immersive mode while the viewer is up with its chrome visible (decision C). */
internal val NAV_HIDDEN_LIGHT_ICONS = barsState(false, navigation = true, lightIcons = true, TRANSIENT)

/** The viewer after a tap: everything hidden. Only the viewer produces it, so the icons are light. */
internal val FULLSCREEN = barsState(true, navigation = true, lightIcons = true, TRANSIENT)

/** Deterministic, network-free images for the real [ImageViewerScreen] mounted by both tests. */
@OptIn(DelicateCoilApi::class)
internal fun installFakeImageLoader(context: Context) {
    val engine = FakeImageLoaderEngine.Builder()
        .intercept(SOURCE_A, ColorImage(android.graphics.Color.BLUE, width = 360, height = 780))
        .intercept(SOURCE_B, ColorImage(android.graphics.Color.GREEN, width = 360, height = 780))
        .build()
    SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
}

/** The REAL viewer — the intent producer under test — on a synthetic image. */
@Composable
internal fun ViewerUnderTest(sourceUrl: String) {
    ImageViewerScreen(
        request = ImageViewerRequest(sourceUrl, sourceUrl, sourceUrl, "photo", false),
        onClose = {},
        onSave = {},
    )
}
