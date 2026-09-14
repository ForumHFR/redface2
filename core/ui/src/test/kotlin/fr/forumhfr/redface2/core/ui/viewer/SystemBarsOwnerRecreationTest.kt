package fr.forumhfr.redface2.core.ui.viewer

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1388 — a REAL activity re-creation (rotation, theme change, process-death restore), the one thing
 * [SystemBarsOwnerTest] can only simulate: here the Activity, its window, its lifecycle, the theme,
 * the intent holder, the producer token AND the [SystemBarsController] are all rebuilt by the
 * platform, exactly as they are on a device.
 *
 * What it proves: the FIRST state written to the NEW window is already the right one. The viewer
 * publishes nothing until its own effects run, so a design that waited for that intent would have
 * flashed « everything visible » — the shell's `viewerRouteActive` seed (the restored back stack says
 * the viewer is the active destination) is what makes the first write correct. It also proves nothing
 * of the previous window leaks in: the re-created window starts from a recomputed state, never from a
 * snapshot taken before the re-creation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class SystemBarsOwnerRecreationTest {

    @Before
    fun registerHostActivity() {
        SystemBarsWindows.reset()
        // The host Activity belongs to the test source set, so it is in no merged manifest.
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager)
            .addActivityIfNotPresent(ComponentName(context, SystemBarsHostActivity::class.java))
    }

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    @Test
    fun `a real re-creation writes the viewer state on the new window without an intermediate frame`() {
        ActivityScenario.launch(SystemBarsHostActivity::class.java).use { scenario ->
            idleMainLooper()

            assertEquals(1, SystemBarsWindows.windows.size)
            assertEquals(NAV_HIDDEN_LIGHT_ICONS, SystemBarsWindows.windows[0].applied.first())

            scenario.recreate()
            idleMainLooper()

            // A brand new Activity, window and composition.
            assertEquals(2, SystemBarsWindows.windows.size)
            val rebuilt = SystemBarsWindows.windows[1].applied

            // THE assertion: the very first write on the new window is the viewer state, not an
            // « everything visible » frame corrected a moment later.
            assertEquals(NAV_HIDDEN_LIGHT_ICONS, rebuilt.first())
            // And it is the ONLY state that window ever receives. Asserted on the distinct sequence
            // rather than on the raw list because the activity is RESUMED, so the owner's ON_RESUME
            // re-assert legitimately repeats the identical state — a different one would show up here.
            assertEquals(listOf(NAV_HIDDEN_LIGHT_ICONS), rebuilt.distinct())
            // The window that went away received nothing more on the way out.
            assertEquals(listOf(NAV_HIDDEN_LIGHT_ICONS), SystemBarsWindows.windows[0].applied.distinct())
        }
    }

    /** Robolectric's looper is PAUSED: drain it so composition and its effects have actually run. */
    private fun idleMainLooper() = shadowOf(Looper.getMainLooper()).idle()
}

/**
 * Every window the platform hands out during the test, in creation order. A static registry is the
 * only way in: [ActivityScenario] builds the Activity itself, so the seam cannot be passed as an
 * argument.
 */
internal object SystemBarsWindows {

    val windows = mutableListOf<RecordingSystemBarsController>()

    fun newWindow() = RecordingSystemBarsController().also { windows += it }

    fun reset() = windows.clear()
}

/**
 * A minimal app shell: the single writer and one real viewer, mounted from `onCreate` the way
 * `MainActivity` mounts `RedfaceApp`, so `ActivityScenario.recreate()` rebuilds the whole thing.
 *
 * The immersive setting is on and the viewer is the active destination, which is the interesting
 * state to restore: the navigation bar must be hidden from the first write (decision C).
 */
internal class SystemBarsHostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installFakeImageLoader(this)
        val controller = SystemBarsWindows.newWindow()
        setContent {
            CompositionLocalProvider(LocalSystemBarsController provides controller) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    SystemBarsOwnerEffect(
                        immersive = true,
                        navBarRevealed = false,
                        // Restored back stack: the shell knows the viewer is up before it publishes.
                        viewerRouteActive = true,
                        darkTheme = false,
                    )
                    ViewerUnderTest(SOURCE_A)
                }
            }
        }
    }
}
