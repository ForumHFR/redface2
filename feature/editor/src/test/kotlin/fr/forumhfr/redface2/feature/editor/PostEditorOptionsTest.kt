package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `message tone row starts collapsed then exposes 16 radio targets and emits intent`() {
        var emitted: PostEditorIntent? = null
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostEditorOptions(
                    signatureEnabled = false,
                    smileyDisabled = false,
                    emailNotificationEnabled = false,
                    msgIcon = 1,
                    enabled = true,
                    onSignatureChanged = {},
                    onSmileyDisabledChanged = {},
                    onEmailNotificationChanged = {},
                    onMsgIconSelected = { emitted = PostEditorIntent.MsgIconSelected(it) },
                )
            }
        }

        composeTestRule.onNodeWithText("Ton du message").assertIsDisplayed()
        composeTestRule.onNodeWithText("Aucun").assertIsDisplayed()
        composeTestRule.onNodeWithTag("$MSG_ICON_OPTION_TAG_PREFIX$DEFAULT_MSG_ICON")
            .assertDoesNotExist()

        composeTestRule.onNodeWithTag(MSG_ICON_PICKER_TOGGLE_TAG).performClick()
        EDITOR_MSG_ICONS.forEach { icon ->
            composeTestRule.onNodeWithTag("$MSG_ICON_OPTION_TAG_PREFIX$icon").assertExists()
        }
        composeTestRule.onNodeWithContentDescription("Aucun ton").assertIsSelected()
        composeTestRule.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}6").performClick()

        assertEquals(PostEditorIntent.MsgIconSelected(6), emitted)
    }
}
