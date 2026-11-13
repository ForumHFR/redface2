package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class PostEditorOptionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder().default(ColorImage(0xFF1565C0.toInt())).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `ED71 real post options expose 16 tones immediately and emit intent`() {
        var emitted: PostEditorIntent? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostEditorTestContent(PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0),
                    onIntent = { emitted = it })
            }
        }

        composeTestRule.onNodeWithContentDescription("Options").performClick()
        composeTestRule.onNodeWithText("Ton du message").assertIsDisplayed()
        composeTestRule.onNodeWithTag("message_icon_picker_toggle").assertDoesNotExist()
        EDITOR_MSG_ICONS.forEach { icon ->
            composeTestRule.onNodeWithTag("$MSG_ICON_OPTION_TAG_PREFIX$icon").assertExists()
        }
        composeTestRule.onNodeWithContentDescription("Aucun").assertIsSelected()
        composeTestRule.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}6").performClick()

        assertEquals(PostEditorIntent.MsgIconSelected(6), emitted)
    }

    @Test
    fun namedOptionRowsEmitExactPostIntents() {
        val emitted = mutableListOf<PostEditorIntent>()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostEditorTestContent(PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0),
                    onIntent = { emitted += it })
            }
        }
        composeTestRule.onNodeWithContentDescription("Options").performClick()
        val cases = listOf(
            "Activer votre signature" to PostEditorIntent.ToggleSignature(true),
            "Désactiver les smilies" to PostEditorIntent.ToggleSmileyDisabled(true),
            "Activer la notification par email du sujet" to PostEditorIntent.ToggleEmailNotification(true),
        )
        val expected = mutableListOf<PostEditorIntent>()
        cases.forEach { (label, intent) ->
            composeTestRule.onNode(isToggleable() and hasText(label)).assertIsOff().assertIsDisplayed()
            composeTestRule.onNodeWithText(label, useUnmergedTree = true).performTouchInput { click() }
            expected += intent
            composeTestRule.runOnIdle { assertEquals(expected, emitted) }
        }
    }
}
