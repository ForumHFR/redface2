package fr.forumhfr.redface2.feature.messages

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
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

/** #1051 — exact capabilities and data-driven metadata of the private-message menu. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MessageMenuSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `copy writes the complete neutral projection and forbidden entries stay absent`() {
        mount(
            message = richMessage(),
            onToggleBlockAuthor = {},
        )

        compose.onNodeWithText("Copier le texte").assertIsDisplayed()
        compose.onNodeWithText("Masquer cet utilisateur").assertIsDisplayed()
        compose.onNodeWithText("Copier le lien de ce post").assertDoesNotExist()
        compose.onNodeWithText("Ouvrir dans le navigateur").assertDoesNotExist()
        compose.onNodeWithText("Citer").assertDoesNotExist()

        compose.onNodeWithText("Copier le texte").performClick()

        val clipboard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "> Texte cité\n> — Alice\n\n[spoiler]\nSecret\n[/spoiler]",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun `an image-only message keeps copy visible but disabled`() {
        mount(
            message = sampleMessage().copy(
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Image(
                            url = "https://images.invalid/private.png",
                            description = "image",
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Copier le texte")
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun `profile hero routes the profile action`() {
        var profileOpens = 0
        mount(
            message = sampleMessage(),
            onOpenProfile = { profileOpens++ },
            onToggleBlockAuthor = {},
        )

        val profileActionLabel = ApplicationProvider.getApplicationContext<Application>()
            .getString(R.string.messages_open_profile_action)
        val profileAction = SemanticsMatcher("has the profile click action") { node ->
            node.config.getOrElseNullable(SemanticsActions.OnClick) { null }
                ?.label == profileActionLabel
        }
        compose.onNode(
            profileAction and hasAnyDescendant(hasText("Alice")),
            useUnmergedTree = true,
        )
            .assertHasClickAction()
            .performClick()

        // The profile callback intentionally runs after the sheet hide animation completes.
        compose.waitUntil(timeoutMillis = 2_000) { profileOpens == 1 }
        assertEquals(1, profileOpens)
    }

    @Test
    fun `block label flips to unblock from the live snapshot`() {
        var toggles = 0
        mount(
            message = sampleMessage(),
            authorBlocked = true,
            onToggleBlockAuthor = { toggles++ },
        )

        compose.onNodeWithText("Masquer cet utilisateur").assertDoesNotExist()
        compose.onNodeWithText("Ne plus masquer cet utilisateur")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `edited and cited information is strictly data driven`() {
        mount(
            message = sampleMessage().copy(
                editedAt = Instant.parse("2025-01-02T03:04:05Z"),
                citedCount = 2,
            ),
        )

        compose.onNodeWithText("Édité le 02/01/2025 04:04:05").assertIsDisplayed()
        compose.onNodeWithText("Cité 2 fois").assertIsDisplayed()
    }

    @Test
    fun `missing edited and cited data emits neither information line`() {
        mount(message = sampleMessage())

        compose.onNodeWithText("Édité le", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cité", substring = true).assertDoesNotExist()
    }

    private fun mount(
        message: Post,
        authorBlocked: Boolean = false,
        onOpenProfile: (() -> Unit)? = null,
        onToggleBlockAuthor: (() -> Unit)? = null,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageMenuSheet(
                    message = message,
                    authorBlocked = authorBlocked,
                    onDismiss = {},
                    onOpenProfile = onOpenProfile,
                    onToggleBlockAuthor = onToggleBlockAuthor,
                )
            }
        }
    }

    private fun richMessage(): Post = sampleMessage().copy(
        content = PostContent(
            blocks = listOf(
                PostBlock.Quote(
                    author = "Alice",
                    numreponse = 1,
                    page = 1,
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(listOf(PostInline.Text("Texte cité"))),
                        ),
                    ),
                ),
                PostBlock.Spoiler(
                    label = "Spoiler",
                    content = PostContent(
                        blocks = listOf(
                            PostBlock.Paragraph(listOf(PostInline.Text("Secret"))),
                        ),
                    ),
                ),
            ),
        ),
    )

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
        profileId = 123,
    )
}
