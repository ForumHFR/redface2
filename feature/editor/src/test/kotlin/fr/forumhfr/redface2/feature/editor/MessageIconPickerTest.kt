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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
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

    @After
    fun resetImageLoader() = SingletonImageLoader.reset()

    @Test
    fun twoRowsOfEightAt360Dp() = assertGrid(360.dp)

    @Test
    @Config(qualifiers = "fr-rFR-w411dp-h780dp-xxhdpi")
    fun twoRowsOfEightAt411Dp() = assertGrid(411.dp)

    @Test
    @Config(qualifiers = "fr-rFR-w640dp-h780dp-xxhdpi")
    fun twoRowsOfEightAt640Dp() = assertGrid(640.dp)

    /** 440 dpi (density 2.75) is the non-integer case that made 30 dp sprites uneven: ×4 = 60 px there too. */
    @Test
    fun spriteStaysAnIntegerMultipleAt440Dpi() = assertGrid(360.dp, screenDensity = 2.75f, spritePx = 60)

    @Test
    fun spriteScaleIsAnIntegerPerDensityAndFitsItsCell() {
        // density -> expected multiple of the 15 px source for the 22 dp target, in a roomy cell.
        mapOf(1f to 1, 1.5f to 2, 2f to 3, 2.625f to 4, 2.75f to 4, 3f to 4, 3.5f to 5, 4f to 6)
            .forEach { (density, scale) ->
                assertEquals("density $density", 15 * scale, messageIconSpritePx(22 * density, roomPx = 1_000))
            }
        assertEquals("lowered to the room", 45, messageIconSpritePx(66f, roomPx = 59))
        assertEquals("never below the source size", 15, messageIconSpritePx(66f, roomPx = 4))
    }

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
    fun eachToneKeepsItsNumberNameSpriteAndSelection() {
        val requestedUrls = ConcurrentHashMap.newKeySet<String>()
        val images = FakeImageLoaderEngine.Builder().addInterceptor { chain ->
            requestedUrls.add(chain.request.data.toString())
            null
        }
        TONE_NAMES.forEachIndexed { index, _ ->
            val icon = index + 1
            images.intercept(expectedSpriteUrl(icon), ColorImage(expectedSpriteColor(icon)))
        }
        install(images.default(ColorImage(0)).build())
        val selected = mutableIntStateOf(1)
        val calls = mutableListOf<Int>()
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                // Selection is supplied separately, as when restoring a value from editor state.
                MessageIconPicker(selected.intValue, true, calls::add)
            }
        }
        compose.runOnIdle {
            assertEquals((1..16).map(::expectedSpriteUrl).toSet(), requestedUrls)
        }
        TONE_NAMES.forEachIndexed { index, name ->
            val icon = index + 1
            val cell = compose.onNodeWithTag("$MSG_ICON_OPTION_TAG_PREFIX$icon")
            cell.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)))
            cell.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            // Distinct URL-specific colours also detect sprites swapped between numbered cells.
            val sprite = compose.onNodeWithTag("$MSG_ICON_SPRITE_TAG_PREFIX$icon", useUnmergedTree = true)
                .captureToImage().asAndroidBitmap()
            assertEquals("Tone $icon must draw ${expectedSpriteUrl(icon)}", expectedSpriteColor(icon),
                sprite.getPixel(sprite.width / 2, sprite.height / 2))

            compose.runOnIdle { selected.intValue = if (icon == 1) 16 else 1 }
            cell.assertIsNotSelected()
            compose.onNodeWithContentDescription(name).performClick()
            compose.runOnIdle { assertEquals((1..icon).toList(), calls) }
            cell.assertIsNotSelected()
            compose.runOnIdle { selected.intValue = icon }
            cell.assertIsSelected()
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(1)
            compose.onNodeWithText(name).assertIsDisplayed()
        }
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
            ErrorResult(image = null, request = chain.request, throwable = IOException("Offline"))
        }.build())
        compose.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                Surface {
                    Column {
                        MessageIconPicker(1, true, calls::add)
                        // Independent reference: the fallback must draw the number, not an empty painter.
                        // 20 dp = the 60 px sprite box (15 px × 4) at xxhdpi.
                        Box(Modifier.size(20.dp).testTag("expected-number"), contentAlignment = Alignment.Center) {
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

    private fun assertGrid(width: Dp, screenDensity: Float? = null, spritePx: Int = 60) {
        val fontScale = mutableStateOf(1f)
        lateinit var density: Density
        compose.setContent {
            density = Density(screenDensity ?: LocalDensity.current.density, fontScale.value)
            CompositionLocalProvider(LocalDensity provides density) {
                RedfaceTheme(darkTheme = false, dynamicColor = false) {
                    Box(Modifier.width(width).padding(horizontal = 16.dp)) {
                        MessageIconPicker(1, true, {}, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        listOf(1f, 1.3f).forEach { scale ->
            compose.runOnIdle { fontScale.value = scale }
            compose.waitForIdle()
            assertGridDimensions(width, density, spritePx)
        }
    }

    private fun assertGridDimensions(width: Dp, density: Density, spritePx: Int) {
        val rows = (1..16).map { pixelBounds("$MSG_ICON_OPTION_TAG_PREFIX$it") }.chunked(COLUMNS)
        val firstRow = rows.first()
        val padding = with(density) { 16.dp.roundToPx() }
        val usefulWidth = with(density) { (width - 32.dp).roundToPx() }
        val targetHeight = with(density) { 48.dp.roundToPx() }
        assertEquals("16 tones in two rows", 2, rows.size)
        assertEquals("Sprite must be an integer multiple of the 15 px source", 0, spritePx % 15)
        rows.forEachIndexed { rowIndex, row ->
            assertEquals("Row $rowIndex is full", COLUMNS, row.size)
            assertEquals("Row $rowIndex left edge", padding, row.first().left)
            row.zipWithNext().forEach { (left, right) ->
                assertEquals("Row $rowIndex cells must be contiguous", left.right, right.left)
            }
            assertEquals("Row $rowIndex useful width", usefulWidth, row.sumOf { it.width })
            assertEquals("Row $rowIndex right edge", padding + usefulWidth, row.last().right)
            row.forEachIndexed { column, cell ->
                val icon = rowIndex * COLUMNS + column + 1
                // One eighth of the useful width, within one rounding pixel: below 48 dp on narrow screens.
                assertTrue("Tone $icon width", cell.width - usefulWidth / COLUMNS in 0..1)
                assertEquals("Tone $icon height", targetHeight, cell.height)
                assertEquals("Tone $icon column left", firstRow[column].left, cell.left)
                assertEquals("Tone $icon column right", firstRow[column].right, cell.right)
                assertEquals("Tone $icon row top", firstRow.first().top + rowIndex * targetHeight, cell.top)
                val sprite = pixelBounds("$MSG_ICON_SPRITE_TAG_PREFIX$icon")
                assertEquals("Tone $icon sprite width", spritePx, sprite.width)
                assertEquals("Tone $icon sprite height", spritePx, sprite.height)
                assertTrue("Tone $icon sprite stays inside its cell", sprite.left >= cell.left &&
                    sprite.right <= cell.right && sprite.top >= cell.top && sprite.bottom <= cell.bottom)
            }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(16)
    }

    private fun pixelBounds(tag: String): IntRect {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        // Actual placed pixels, without Compose's automatic minimum touch expansion.
        val left = node.positionInRoot.x.roundToInt()
        val top = node.positionInRoot.y.roundToInt()
        return IntRect(left, top, left + node.size.width, top + node.size.height)
    }

    private fun install(engine: FakeImageLoaderEngine) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    private fun expectedSpriteUrl(icon: Int) = "https://forum-images.hardware.fr/icones/message/icon$icon.gif"

    private fun expectedSpriteColor(icon: Int) = 0xFF000000.toInt() or (icon * 0x000B0B0B)

    private companion object {
        const val COLUMNS = 8
        val TONE_NAMES = listOf(
            "Aucun", "Flèche", "Idée", "Attention", "Question", "Cool", "Sourire", "En colère",
            "Triste", "Rire", "Choqué", "Clin d'œil", "Pouce bas", "Pouce haut", "Étonné", "Blasé",
        )
    }
}
