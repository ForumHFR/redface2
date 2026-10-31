package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerFavoritesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val favorites = listOf("[:fictif_z:3]", "[:fictif_a]").mapIndexed { index, token ->
        EditorSmiley(token, "https://example.invalid/fictif_$index.gif", EditorSmileySource.HFR_FAVORITE)
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val engine = FakeImageLoaderEngine.Builder().apply {
            (favorites + BUILTIN_HFR_SMILEYS).forEach { smiley ->
                intercept(smiley.imageUrl, ColorImage(0xFF808080.toInt(), width = 70, height = 50))
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `HFR favorites keep form order in Favorites and Standard contains only builtins`() {
        val controller = controller(favorites)
        var inserted: String? = null
        composeSheet(controller) { token ->
            inserted = token
            controller.dismiss()
        }

        val favoritesTitle = context.getString(R.string.editor_smiley_section_favorites)
        val standardTitle = context.getString(R.string.editor_smiley_tab_standard)
        val titleTop = composeTestRule.onNodeWithText(favoritesTitle).fetchSemanticsNode().boundsInRoot.top
        val first = composeTestRule.onNodeWithContentDescription(description(favorites[0]))
        val second = composeTestRule.onNodeWithContentDescription(description(favorites[1]))
        val firstBounds = first.fetchSemanticsNode().boundsInRoot
        val secondBounds = second.fetchSemanticsNode().boundsInRoot
        assertTrue(titleTop < firstBounds.top)
        assertTrue(firstBounds.left < secondBounds.left)
        composeTestRule.onNodeWithContentDescription(description(BUILTIN_HFR_SMILEYS.first())).assertDoesNotExist()
        composeTestRule.onNodeWithText(standardTitle).performClick()
        composeTestRule.onNodeWithText(favoritesTitle).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription(description(BUILTIN_HFR_SMILEYS.first())).assertExists()
        first.assertDoesNotExist()
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_tab_favorites)).performClick()

        first.performClick()
        composeTestRule.runOnIdle {
            assertEquals("[:fictif_z:3]", inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
    }

    @Test
    fun `empty favorites render no favorites heading and keep the Standard grid`() {
        composeSheet(controller(emptyList())) {}
        composeTestRule.onNodeWithText(context.getString(R.string.editor_smiley_section_favorites))
            .assertDoesNotExist()
        composeTestRule.onAllNodesWithText(context.getString(R.string.editor_smiley_tab_standard))
            .assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription(description(BUILTIN_HFR_SMILEYS.first())).assertExists()
    }

    private fun controller(items: List<EditorSmiley>) = SmileyPickerController(
        scope = CoroutineScope(StandardTestDispatcher()),
        searchWiki = { _, _ -> error("Standard must not search the wiki") },
        favoriteSources = SmileyPickerFavoriteSources(favoriteSmileys = { items }),
    ).also { it.open() }

    private fun description(smiley: EditorSmiley) =
        context.getString(R.string.editor_smiley_insert_description, smiley.token)

    private fun composeSheet(controller: SmileyPickerController, onSelected: (String) -> Unit) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val state by controller.state.collectAsState()
                (state as? SmileyPickerState.Open)?.let { open ->
                    SmileyPickerSheet(
                        state = open,
                        onDismiss = controller::dismiss,
                        onQueryChange = controller::onQueryChanged,
                        onSmileyClicked = onSelected,
                    )
                }
            }
        }
    }
}
