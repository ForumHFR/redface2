package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1417: the useful width excludes the Options sheet's existing 16 dp side padding. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class, coil3.annotation.DelicateCoilApi::class)
class MessageIconPickerTest {
    @get:Rule val compose = createComposeRule()

    @Before
    fun installImages() = install(FakeImageLoaderEngine.Builder().default(ColorImage(0xFF1565C0.toInt())).build())

    @Test
    fun sixColumnsAt360Dp() = assertGrid(360.dp, columns = 6)

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h780dp-xxhdpi")
    fun sixColumnsAt411Dp() = assertGrid(411.dp, columns = 6)

    @Test
    @Config(qualifiers = "fr-rFR-w640dp-h780dp-xxhdpi")
    fun eightColumnsAt640Dp() = assertGrid(640.dp, columns = 8)

    @Test
    @Config(qualifiers = "fr-rFR-w640dp-h780dp-xxhdpi")
    fun sixColumnsBelow384UsefulDp() = assertGrid(415.dp, columns = 6)

    @Test
    @Config(qualifiers = "fr-rFR-w640dp-h780dp-xxhdpi")
    fun eightColumnsFrom384UsefulDp() = assertGrid(416.dp, columns = 8)

    @Test
    fun namedRadiosSelectOneToneAndUpdateTheNonClickableHeader() {
        val selected = mutableIntStateOf(1)
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                MessageIconPicker(selected.intValue, true, { selected.intValue = it })
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)).assertExists()
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertCountEquals(16)
        TONE_NAMES.forEachIndexed { index, name ->
            compose.onNodeWithContentDescription(name)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, index == 0))
        }
        compose.onNodeWithTag(MSG_ICON_HEADER_TAG).assert(hasClickAction().not())
        compose.onNodeWithText("Aucun").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clin d'œil").performClick()
        compose.onNodeWithContentDescription("Clin d'œil").assertIsSelected()
        compose.onNodeWithContentDescription("Aucun").assertIsNotSelected()
        compose.onNodeWithText("Clin d'œil").assertIsDisplayed()
        assertEquals(12, selected.intValue)
    }

    @Test
    fun disabledPickerKeepsItsSelectionAndDoesNotEmit() {
        val enabled = mutableStateOf(true)
        val calls = mutableListOf<Int>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                MessageIconPicker(6, enabled.value, calls::add)
            }
        }
        compose.runOnIdle { enabled.value = false }
        TONE_NAMES.forEach { compose.onNodeWithContentDescription(it).assertIsNotEnabled() }
        compose.onNodeWithContentDescription("Cool").assertIsSelected()
        compose.onNodeWithContentDescription("Rire").performTouchInput { click() }
        compose.runOnIdle { assertTrue(calls.isEmpty()) }
    }

    @Test
    fun loadingAndErrorDisplayTheToneNumberAndRemainSelectable() {
        val release = CompletableDeferred<Unit>()
        val failures = AtomicInteger()
        val calls = mutableListOf<Int>()
        install(FakeImageLoaderEngine.Builder().default { chain ->
            release.await()
            failures.incrementAndGet()
            ErrorResult(request = chain.request, throwable = IOException("Offline"))
        }.build())
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Surface {
                    Column {
                        MessageIconPicker(1, true, calls::add)
                        // Independent reference: the fallback must draw the number, not an empty painter.
                        Box(Modifier.size(30.dp).testTag("expected-number"), contentAlignment = Alignment.Center) {
                            Text("16", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        assertNumberMatchesReference()
        compose.onNodeWithContentDescription("Blasé").performClick()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil { failures.get() == 16 }
        compose.mainClock.advanceTimeBy(1_000L) // Let the selection ripple settle before comparing pixels.
        compose.waitForIdle()
        assertNumberMatchesReference()
        compose.onNodeWithContentDescription("Blasé").performClick()
        compose.runOnIdle { assertEquals(listOf(16, 16), calls) }
    }

    private fun assertNumberMatchesReference() {
        val actual = compose.onNodeWithTag("${MSG_ICON_SPRITE_TAG_PREFIX}16", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        val expected = compose.onNodeWithTag("expected-number").captureToImage().asAndroidBitmap()
        assertTrue("Tone 16 must show its number during loading and after failure", expected.sameAs(actual))
    }

    private fun assertGrid(width: Dp, columns: Int) {
        lateinit var density: Density
        compose.setContent {
            density = LocalDensity.current
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Box(Modifier.width(width).padding(horizontal = 16.dp)) {
                    MessageIconPicker(1, true, {}, Modifier.fillMaxWidth())
                }
            }
        }
        val cells = (1..16).map { icon ->
            val node = compose.onNodeWithTag("$MSG_ICON_OPTION_TAG_PREFIX$icon").fetchSemanticsNode()
            // Actual placed bounds, without Compose's automatic minimum touch expansion.
            with(density) {
                DpRect(node.positionInRoot.x.toDp(), node.positionInRoot.y.toDp(),
                    (node.positionInRoot.x + node.size.width).toDp(),
                    (node.positionInRoot.y + node.size.height).toDp())
            }
        }
        val expectedWidth = (width.value - 32f) / columns
        cells.forEachIndexed { index, cell ->
            assertTrue("Tone ${index + 1} width", cell.width >= 48.dp)
            assertEquals(48f, cell.height.value, 0.01f)
            assertEquals(expectedWidth, cell.width.value, 0.34f)
            assertEquals(16f + index % columns * expectedWidth, cell.left.value, 0.34f)
            assertEquals(cells.first().top.value + index / columns * 48f, cell.top.value, 0.01f)
            val sprite = compose.onNodeWithTag("$MSG_ICON_SPRITE_TAG_PREFIX${index + 1}", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
            assertEquals(30.dp, sprite.width)
            assertEquals(30.dp, sprite.height)
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(16)
    }

    private fun install(engine: FakeImageLoaderEngine) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private companion object {
        val TONE_NAMES = listOf(
            "Aucun", "Flèche", "Idée", "Attention", "Question", "Cool", "Sourire", "En colère",
            "Triste", "Rire", "Choqué", "Clin d'œil", "Pouce bas", "Pouce haut", "Étonné", "Blasé",
        )
    }
}
