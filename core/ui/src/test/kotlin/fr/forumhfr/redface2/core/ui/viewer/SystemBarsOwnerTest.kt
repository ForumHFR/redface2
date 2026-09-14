package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.preferences.ViewerBarsState
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1388 — which system bars the viewer asks the window for, per (#518 setting × chrome visibility),
 * and what it restores on the way out.
 *
 * Robolectric 4.16.1 ships no `ShadowWindowInsetsController`, so the requested state is only
 * observable through the injected [ViewerSystemBarsController] seam. The pure decision itself lives
 * in `:core:domain` (`ViewerSystemBarsTest`); this test pins the WIRING: entry, toggle, ON_RESUME
 * re-assert and restore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class ImageViewerSystemBarsTest {

    @get:Rule
    val compose = createComposeRule()

    private var mounted by mutableStateOf(true)

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    @Test
    fun `opening without the immersive setting leaves both bars alone`() {
        val controller = RecordingSystemBarsController()

        mountViewer(controller, immersive = false)

        assertOnly(controller, bars(status = false, navigation = false), BEHAVIOR_DEFAULT)
    }

    @Test
    fun `opening with the immersive setting keeps the navigation bar hidden`() {
        val controller = RecordingSystemBarsController(
            visibility = ViewerBarsVisibility(statusBarVisible = true, navigationBarVisible = false),
            entryBehavior = TRANSIENT,
        )

        mountViewer(controller, immersive = true)

        assertOnly(controller, bars(status = false, navigation = true), TRANSIENT)
    }

    @Test
    fun `a tap hides the chrome and both system bars together, and a second tap brings them back`() {
        val controller = RecordingSystemBarsController()
        mountViewer(controller, immersive = false)

        tapImage()

        assertEquals(bars(status = true, navigation = true), controller.applied.last().bars)
        assertEquals(TRANSIENT, controller.applied.last().behavior)

        tapImage()

        assertEquals(bars(status = false, navigation = false), controller.applied.last().bars)
        assertEquals(BEHAVIOR_DEFAULT, controller.applied.last().behavior)
    }

    @Test
    fun `the transient swipe behaviour is requested exactly while a bar is hidden`() {
        val controller = RecordingSystemBarsController()
        mountViewer(controller, immersive = false)

        tapImage()
        tapImage()

        assertTrue(
            controller.applied.all { (it.behavior == TRANSIENT) == it.bars.anyHidden },
        )
    }

    @Test
    fun `leaving the viewer restores the bars and the behaviour it found`() {
        val controller = RecordingSystemBarsController(
            visibility = ViewerBarsVisibility(statusBarVisible = true, navigationBarVisible = false),
            entryBehavior = TRANSIENT,
        )
        mountViewer(controller, immersive = true)
        tapImage()

        compose.runOnIdle { mounted = false }
        compose.waitForIdle()

        assertEquals(bars(status = false, navigation = true), controller.applied.last().bars)
        assertEquals(TRANSIENT, controller.applied.last().behavior)
    }

    @Test
    fun `an unreadable first frame restores the app state instead of guessing both bars visible`() {
        val controller = RecordingSystemBarsController(visibility = null, entryBehavior = TRANSIENT)
        mountViewer(controller, immersive = true)

        compose.runOnIdle { mounted = false }
        compose.waitForIdle()

        // The historical fallback claimed « both bars were visible » and SHOWED the navigation bar
        // of an immersive user on exit; the policy at chromeVisible = true is the right answer.
        assertEquals(bars(status = false, navigation = true), controller.applied.last().bars)
    }

    @Test
    fun `an unreadable first frame without the setting restores plain visible bars`() {
        val controller = RecordingSystemBarsController(visibility = null)
        mountViewer(controller, immersive = false)

        compose.runOnIdle { mounted = false }
        compose.waitForIdle()

        assertEquals(bars(status = false, navigation = false), controller.applied.last().bars)
    }

    private fun assertOnly(controller: RecordingSystemBarsController, bars: ViewerBarsState, behavior: Int) {
        assertTrue("no bar state was applied", controller.applied.isNotEmpty())
        // ON_RESUME re-asserts the same state on mount; every application must agree.
        assertTrue(
            "unexpected applications: ${controller.applied}",
            controller.applied.all { it.bars == bars && it.behavior == behavior },
        )
    }

    private fun bars(status: Boolean, navigation: Boolean) =
        ViewerBarsState(hideStatusBar = status, hideNavigationBar = navigation)

    private fun tapImage() {
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun mountViewer(controller: ViewerSystemBarsController, immersive: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(SOURCE_URL, ColorImage(android.graphics.Color.BLUE, width = 360, height = 780))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
        compose.setContent {
            CompositionLocalProvider(LocalViewerSystemBarsController provides controller) {
                RedfaceTheme(
                    darkTheme = false,
                    amoledTheme = false,
                    dynamicColor = false,
                    hideSystemNavBar = immersive,
                ) {
                    if (mounted) ViewerUnderTest()
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun ViewerUnderTest() {
        ImageViewerScreen(
            request = ImageViewerRequest(SOURCE_URL, SOURCE_URL, SOURCE_URL, "photo", diskCache = false),
            onClose = {},
            onSave = {},
        )
    }
}

/** Records what the viewer asked the window for; no Robolectric shadow exposes it. */
private class RecordingSystemBarsController(
    private val visibility: ViewerBarsVisibility? =
        ViewerBarsVisibility(statusBarVisible = true, navigationBarVisible = true),
    private val entryBehavior: Int = BEHAVIOR_DEFAULT,
) : ViewerSystemBarsController {

    val applied = mutableListOf<AppliedBars>()

    override fun currentVisibility(): ViewerBarsVisibility? = visibility

    override fun currentBehavior(): Int = entryBehavior

    override fun applyBars(bars: ViewerBarsState, behavior: Int) {
        applied += AppliedBars(bars, behavior)
    }
}

private data class AppliedBars(val bars: ViewerBarsState, val behavior: Int)

private const val SOURCE_URL = "https://images.example.org/bars.jpg"
private val BEHAVIOR_DEFAULT = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
private val TRANSIENT = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
