package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.preferences.SystemBarsState
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
 * #518 + #1388 — integration test of the two halves: the fullscreen viewer publishing its intent and
 * the shell's [SystemBarsOwnerEffect] writing the window. Robolectric 4.16.1 ships no
 * `ShadowWindowInsetsController`, so the requested state is observed through the injected
 * [SystemBarsController] seam.
 *
 * What it is here to prove — the single-writer invariant, reviewed on PR #1392:
 * - the SHELL is always the last writer, in particular when the viewer leaves;
 * - an overlay or a modal route ABOVE the viewer does not hand the window back to the shell's own
 *   state, because the viewer is still composed;
 * - replacing the controller (activity re-creation) re-applies the CURRENT state, never a snapshot;
 * - a resume after an external change re-applies the current state.
 *
 * Assertions are on the ORDERED sequence of distinct states, never on `all { }`: the order and the
 * moment of each write is exactly what was wrong before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, DelicateCoilApi::class)
class SystemBarsOwnerTest {

    @get:Rule
    val compose = createComposeRule()

    private var immersive by mutableStateOf(false)
    private var navBarRevealed by mutableStateOf(false)
    private var viewerRouteActive by mutableStateOf(false)
    private var viewerMounted by mutableStateOf(false)
    private var controller by mutableStateOf(RecordingSystemBarsController())
    private val lifecycleOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    @Test
    fun `without the viewer the shell applies the historical 518 state`() {
        mountShell()

        assertEquals(listOf(NOTHING_HIDDEN), controller.transitions)

        update { immersive = true }

        assertEquals(listOf(NOTHING_HIDDEN, NAV_HIDDEN), controller.transitions)

        update { navBarRevealed = true }

        assertEquals(listOf(NOTHING_HIDDEN, NAV_HIDDEN, NOTHING_HIDDEN), controller.transitions)
    }

    @Test
    fun `open, tap and close write in order and the shell writes last`() {
        mountShell()
        openViewer()

        assertEquals(listOf(NOTHING_HIDDEN), controller.transitions)

        tapImage()

        assertEquals(listOf(NOTHING_HIDDEN, FULLSCREEN), controller.transitions)

        // The pop flips the shell's top route while the viewer is still composed for its ~200 ms exit
        // transition. Nothing must change: the viewer still owns the intent.
        update { viewerRouteActive = false }

        assertEquals(listOf(NOTHING_HIDDEN, FULLSCREEN), controller.transitions)

        // Real dispose: the intent drops and the SHELL applies its own state, last.
        update { viewerMounted = false }

        assertEquals(listOf(NOTHING_HIDDEN, FULLSCREEN, NOTHING_HIDDEN), controller.transitions)
        assertEquals(NOTHING_HIDDEN, controller.applied.last())
    }

    @Test
    fun `closing from fullscreen with the immersive setting hands back the immersive state`() {
        immersive = true
        mountShell()
        openViewer()
        tapImage()
        update {
            viewerRouteActive = false
            viewerMounted = false
        }

        assertEquals(listOf(NAV_HIDDEN, FULLSCREEN, NAV_HIDDEN), controller.transitions)
    }

    @Test
    fun `an overlay above the viewer keeps its fullscreen`() {
        mountShell()
        openViewer()
        tapImage()

        // A bottom sheet or a modal route on top: the shell's active destination is no longer the
        // viewer, but the viewer stays composed, so its intent — and its fullscreen — stand.
        update { viewerRouteActive = false }
        val duringOverlay = controller.applied.size

        // Closing the overlay changes no key at all; nothing must be re-written either.
        update { viewerRouteActive = true }

        assertEquals(listOf(NOTHING_HIDDEN, FULLSCREEN), controller.transitions)
        assertEquals(FULLSCREEN, controller.applied.last())
        assertEquals("the overlay must not trigger any window write", duringOverlay, controller.applied.size)
    }

    @Test
    fun `replacing the controller re-applies the current state instead of a stale snapshot`() {
        mountShell()
        openViewer()
        tapImage()

        // Activity re-creation: a brand-new window controller, the viewer still mounted.
        val replacement = RecordingSystemBarsController()
        update { controller = replacement }

        assertEquals(listOf(FULLSCREEN), replacement.transitions)

        update {
            viewerMounted = false
            viewerRouteActive = false
        }

        assertEquals(listOf(FULLSCREEN, NOTHING_HIDDEN), replacement.transitions)
    }

    @Test
    fun `a resume after an external change re-applies the current state`() {
        mountShell()
        openViewer()
        tapImage()
        val beforeResume = controller.applied.size

        // Android restored the bars behind our back while the share sheet / browser was up.
        update {
            lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
            lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        }

        assertTrue(
            "ON_RESUME must re-assert the window state",
            controller.applied.size > beforeResume,
        )
        assertEquals(FULLSCREEN, controller.applied.last())
        assertEquals(listOf(NOTHING_HIDDEN, FULLSCREEN), controller.transitions)
    }

    @Test
    fun `the transient behaviour is requested exactly while a bar is hidden`() {
        immersive = true
        mountShell()
        openViewer()
        tapImage()
        tapImage()
        update { immersive = false }

        assertTrue(
            "behaviour must follow anyHidden, got ${controller.applied}",
            controller.applied.all { (it.behavior == TRANSIENT) == it.bars.anyHidden },
        )
        // Leaving immersive mode must restore BEHAVIOR_DEFAULT, not keep the transient one.
        assertEquals(NOTHING_HIDDEN, controller.applied.last())
    }

    private fun openViewer() = update {
        viewerRouteActive = true
        viewerMounted = true
    }

    /** Every post-mount mutation goes through the test clock, so each assertion sees a settled tree. */
    private fun update(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun tapImage() {
        compose.onNodeWithTag(IMAGE_VIEWER_IMAGE_TAG).performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun mountShell() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(SOURCE_URL, ColorImage(android.graphics.Color.BLUE, width = 360, height = 780))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(
                LocalSystemBarsController provides controller,
                LocalLifecycleOwner provides lifecycleOwner,
            ) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    SystemBarsOwnerEffect(
                        immersive = immersive,
                        navBarRevealed = navBarRevealed,
                        viewerRouteActive = viewerRouteActive,
                    )
                    if (viewerMounted) {
                        ImageViewerScreen(
                            request = ImageViewerRequest(SOURCE_URL, SOURCE_URL, SOURCE_URL, "photo", false),
                            onClose = {},
                            onSave = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}

/** Records every window write, in order; nothing else in the app may write the bars. */
private class RecordingSystemBarsController : SystemBarsController {

    val applied = mutableListOf<AppliedBars>()

    /**
     * The applied states with consecutive duplicates removed: a re-assert of the SAME state (an
     * ON_RESUME, a recomposition) is not a transition, while an unwanted flip always is.
     */
    val transitions: List<AppliedBars>
        get() = applied.filterIndexed { index, value -> index == 0 || applied[index - 1] != value }

    override fun applyBars(bars: SystemBarsState, behavior: Int) {
        applied += AppliedBars(bars, behavior)
    }
}

private data class AppliedBars(val bars: SystemBarsState, val behavior: Int)

private fun barsState(status: Boolean, navigation: Boolean, behavior: Int) =
    AppliedBars(SystemBarsState(hideStatusBar = status, hideNavigationBar = navigation), behavior)

private const val SOURCE_URL = "https://images.example.org/bars.jpg"
private val DEFAULT_BEHAVIOR = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
private val TRANSIENT = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
private val NOTHING_HIDDEN = barsState(status = false, navigation = false, behavior = DEFAULT_BEHAVIOR)
private val NAV_HIDDEN = barsState(status = false, navigation = true, behavior = TRANSIENT)
private val FULLSCREEN = barsState(status = true, navigation = true, behavior = TRANSIENT)
