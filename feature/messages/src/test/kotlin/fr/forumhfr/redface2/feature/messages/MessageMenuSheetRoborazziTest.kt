package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.isDialog
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** New menu references must be recorded and inspected explicitly; tests never generate baselines. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MessageMenuSheetRoborazziTest {
    @get:Rule val compose = createComposeRule()

    @Test fun editableMenuLight() = capture(false, "private_message_menu_editable_light")
    @Test fun editableMenuDark() = capture(true, "private_message_menu_editable_dark")

    private fun capture(dark: Boolean, name: String) {
        val message = Post(
            numreponse = 101,
            author = "Synthetic author",
            date = Instant.EPOCH,
            content = PostContent(listOf(PostBlock.Paragraph(listOf(PostInline.Text("Synthetic body"))))),
            avatarUrl = null,
            isEditable = true,
            isOwnPost = true,
            quotedAuthors = emptyList(),
            postIndex = null,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = dark, amoledTheme = false, dynamicColor = false) {
                MessageMenuSheet(message, authorBlocked = false, contentVisible = true, onDismiss = {},
                    onEdit = {}, onToggleMultiQuote = {})
            }
        }
        compose.waitForIdle()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/$name.png")
    }
}
