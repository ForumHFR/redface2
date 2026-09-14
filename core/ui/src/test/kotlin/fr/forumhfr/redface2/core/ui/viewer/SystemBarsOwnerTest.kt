package fr.forumhfr.redface2.core.ui.viewer

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #286 + #518 + #1388 — integration test of the two halves: the fullscreen viewer publishing its
 * intent and the shell's [SystemBarsOwnerEffect] writing the window. Robolectric 4.16.1 ships no
 * `ShadowWindowInsetsController`, so the requested state is observed through the injected
 * [SystemBarsController] seam.
 *
 * Every assertion is on the EXACT, ordered list of writes — no de-duplication — because « how many
 * times and when » is precisely what the reviewed defects were about: a write that should not happen
 * shows up as an extra entry, and a missing re-assert as a missing one. To keep those lists readable
 * the fake lifecycle is mounted at CREATED, so mounting writes once and each `ON_RESUME` is an
 * explicit step of the test.
 *
 * A REAL activity re-creation is covered by [SystemBarsOwnerRecreationTest]; the « simulated » test
 * here keeps the cheaper version of the same question (a rebuilt holder / token / controller inside
 * one running composition), because the two fail for different reasons.
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
    private var darkTheme by mutableStateOf(false)
    private var viewerRouteActive by mutableStateOf(false)
    private var viewerAMounted by mutableStateOf(false)
    private var viewerBMounted by mutableStateOf(false)
    private var recreationKey by mutableStateOf(0)
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

        assertEquals(listOf(VISIBLE_DARK_ICONS), controller.applied)

        update { immersive = true }

        assertEquals(listOf(VISIBLE_DARK_ICONS, NAV_HIDDEN_DARK_ICONS), controller.applied)

        update { navBarRevealed = true }

        assertEquals(
            listOf(VISIBLE_DARK_ICONS, NAV_HIDDEN_DARK_ICONS, VISIBLE_DARK_ICONS),
            controller.applied,
        )
    }

    @Test
    fun `the viewer switches the icons to light and the shell hands the theme contrast back`() {
        // #1388 — the viewer paints a black backdrop and the bars are now VISIBLE above it, so a light
        // theme would draw a black clock and black glyphs on black. The contrast is part of the same
        // single write, and it is RECOMPUTED on the way out, not restored from a snapshot.
        mountShell()
        openViewer()

        assertEquals(listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS), controller.applied)

        update {
            viewerRouteActive = false
            viewerAMounted = false
        }

        assertEquals(
            listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, VISIBLE_DARK_ICONS),
            controller.applied,
        )
    }

    @Test
    fun `in a dark theme the icons are already light so opening the viewer writes nothing`() {
        darkTheme = true
        mountShell()

        assertEquals(listOf(VISIBLE_LIGHT_ICONS), controller.applied)

        openViewer()

        assertEquals(listOf(VISIBLE_LIGHT_ICONS), controller.applied)
    }

    @Test
    fun `open, tap and close write in order and the shell writes last`() {
        mountShell()
        openViewer()

        // Nothing is hidden yet — only the icon contrast follows the viewer's black backdrop.
        assertEquals(listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS), controller.applied)

        tapViewer()

        assertEquals(listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN), controller.applied)

        // The pop flips the shell's top route while the viewer is still composed for its ~200 ms exit
        // transition: ZERO write, the viewer still owns the intent.
        update { viewerRouteActive = false }

        assertEquals(listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN), controller.applied)

        // Real dispose: the intent drops and the SHELL applies its own state, last.
        update { viewerAMounted = false }

        assertEquals(
            listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN, VISIBLE_DARK_ICONS),
            controller.applied,
        )
    }

    @Test
    fun `closing from fullscreen with the immersive setting hands back the immersive state`() {
        immersive = true
        mountShell()
        openViewer()
        tapViewer()
        update {
            viewerRouteActive = false
            viewerAMounted = false
        }

        assertEquals(
            listOf(NAV_HIDDEN_DARK_ICONS, NAV_HIDDEN_LIGHT_ICONS, FULLSCREEN, NAV_HIDDEN_DARK_ICONS),
            controller.applied,
        )
    }

    @Test
    fun `an overlay above the viewer keeps its fullscreen without a single write`() {
        mountShell()
        openViewer()
        tapViewer()
        val opened = listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN)

        // A bottom sheet or a modal route on top: the shell's active destination is no longer the
        // viewer, but the viewer stays composed, so its intent — and its fullscreen — stand.
        update { viewerRouteActive = false }
        // Closing the overlay changes no fact either.
        update { viewerRouteActive = true }

        assertEquals(opened, controller.applied)
    }

    @Test
    fun `opening from a topic whose navigation bar was revealed hides it at once`() {
        immersive = true
        navBarRevealed = true
        mountShell()

        // Reading screen: the scroll reveal brought the immersive bar back.
        assertEquals(listOf(VISIBLE_DARK_ICONS), controller.applied)

        openViewer()

        // Decision C: inside the viewer the reveal is ignored, so ONE write, straight to nav hidden —
        // no intermediate frame with a visible navigation bar.
        assertEquals(listOf(VISIBLE_DARK_ICONS, NAV_HIDDEN_LIGHT_ICONS), controller.applied)
    }

    @Test
    fun `two overlapping viewers keep the topmost intent when the older one leaves`() {
        mountShell()
        openViewer()
        update { viewerBMounted = true }

        assertEquals(listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS), controller.applied)

        // The newer viewer goes fullscreen while the older one is still composed (transition).
        tapViewer(index = 1)
        val opened = listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN)

        assertEquals(opened, controller.applied)

        // The older one leaves: it must withdraw only ITS registration. A global active/chrome pair
        // published « chrome visible » here and broke the newer viewer's fullscreen.
        update { viewerAMounted = false }

        assertEquals(opened, controller.applied)
    }

    @Test
    fun `toggling the immersive setting during the viewer writes each step in order`() {
        mountShell()
        openViewer()
        tapViewer()
        val opened = listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN)

        // Already fullscreen: turning the setting on changes nothing, so it must not write.
        update { immersive = true }

        assertEquals(opened, controller.applied)

        tapViewer()

        assertEquals(opened + NAV_HIDDEN_LIGHT_ICONS, controller.applied)

        update { immersive = false }

        assertEquals(opened + NAV_HIDDEN_LIGHT_ICONS + VISIBLE_LIGHT_ICONS, controller.applied)
    }

    @Test
    fun `a resume after an external change re-applies the current state`() {
        mountShell()
        openViewer()
        tapViewer()
        val opened = listOf(VISIBLE_DARK_ICONS, VISIBLE_LIGHT_ICONS, FULLSCREEN)

        // Android restored the bars behind our back while the share sheet / browser was up.
        resumeAfterPause()

        assertEquals(opened + FULLSCREEN, controller.applied)

        resumeAfterPause()

        assertEquals(opened + FULLSCREEN + FULLSCREEN, controller.applied)
    }

    @Test
    fun `a simulated re-creation rebuilds holder, token and controller and re-applies the state`() {
        immersive = true
        mountShell()
        openViewer()
        tapViewer()
        val opened = listOf(NAV_HIDDEN_DARK_ICONS, NAV_HIDDEN_LIGHT_ICONS, FULLSCREEN)

        assertEquals(opened, controller.applied)

        val previous = controller
        val replacement = RecordingSystemBarsController()
        update {
            recreationKey++
            controller = replacement
        }

        // The rebuilt viewer starts with its chrome visible (`actionsVisible` is not saved either), and
        // the shell's own route seed carries the viewer state on the very first frame. The new window
        // controller therefore receives the CURRENT state, never the stale fullscreen.
        assertEquals(listOf(NAV_HIDDEN_LIGHT_ICONS), replacement.applied)
        assertEquals(opened, previous.applied)
    }

    private fun openViewer() = update {
        viewerRouteActive = true
        viewerAMounted = true
    }

    private fun tapViewer(index: Int = 0) {
        compose.onAllNodesWithTag(IMAGE_VIEWER_IMAGE_TAG)[index]
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitForIdle()
    }

    private fun resumeAfterPause() = update {
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        lifecycleOwner.registry.currentState = Lifecycle.State.RESUMED
    }

    /** Every post-mount mutation goes through the test clock, so each assertion sees a settled tree. */
    private fun update(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private fun mountShell() {
        installFakeImageLoader(ApplicationProvider.getApplicationContext<Context>())
        // CREATED, not RESUMED: mounting then writes exactly once, and every ON_RESUME is an explicit step.
        lifecycleOwner.registry.currentState = Lifecycle.State.CREATED
        compose.setContent {
            CompositionLocalProvider(
                LocalSystemBarsController provides controller,
                LocalLifecycleOwner provides lifecycleOwner,
            ) {
                // The whole themed subtree — holder included — is rebuilt when the key changes.
                key(recreationKey) {
                    RedfaceTheme(darkTheme = darkTheme, amoledTheme = false, dynamicColor = false) {
                        SystemBarsOwnerEffect(
                            immersive = immersive,
                            navBarRevealed = navBarRevealed,
                            viewerRouteActive = viewerRouteActive,
                            darkTheme = darkTheme,
                        )
                        if (viewerAMounted) ViewerUnderTest(SOURCE_A)
                        if (viewerBMounted) ViewerUnderTest(SOURCE_B)
                    }
                }
            }
        }
        compose.waitForIdle()
    }
}
