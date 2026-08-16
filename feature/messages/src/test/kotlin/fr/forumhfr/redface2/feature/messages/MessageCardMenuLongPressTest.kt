package fr.forumhfr.redface2.feature.messages

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1051 — physical long-press seam from the MP card to its feature-owned menu host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MessageCardMenuLongPressTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `long press on a message routes the menu action`() {
        var menuOpens = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageCard(
                    message = sampleMessage(),
                    onOpenProfile = {},
                    onOpenMenu = { menuOpens++ },
                )
            }
        }

        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
            useUnmergedTree = true,
        ).assertExists()
        // The author is profile-clickable in production. Long-press the inert date area of the same
        // identity header so the test proves the card gesture without weakening the profile tap.
        compose.onNodeWithText("01/01/1970 01:00:00").performTouchInput { longClick() }

        assertEquals(1, menuOpens)
    }

    private fun sampleMessage(): Post = Post(
        numreponse = 42,
        author = "Alice",
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text("Bonjour")))),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )
}
