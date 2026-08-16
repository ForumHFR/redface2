package fr.forumhfr.redface2.feature.topic

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
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

/** #1051 — topic-side symmetry for full-text copy, without dropping existing post-menu actions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1600dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostMenuSheetTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `copy text writes the complete post projection`() {
        mount(post = samplePost())

        compose.onNodeWithText("Copier le texte").performClick()

        val clipboard = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(
            "Premier paragraphe\n\nSecond paragraphe",
            clipboard.primaryClip?.getItemAt(0)?.text?.toString(),
        )
    }

    @Test
    fun `image-only post keeps the new copy action disabled`() {
        mount(
            post = samplePost().copy(
                content = PostContent(
                    listOf(
                        PostBlock.Image(
                            url = "https://images.invalid/topic.png",
                            description = null,
                        ),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Copier le texte").assertIsNotEnabled()
    }

    @Test
    fun `copy addition leaves every simultaneously available topic action composed`() {
        mount(
            post = samplePost().copy(
                editedAt = Instant.parse("2025-01-02T03:04:05Z"),
                citedCount = 2,
            ),
            withAllActions = true,
        )

        compose.onNodeWithText("Alice").assertHasClickAction()
        listOf(
            "Copier le lien de ce post",
            "Copier le texte",
            "Ouvrir dans le navigateur",
            "Mettre un favori HFR ici",
            "Modifier le premier message",
            "Ajouter à la citation multiple",
            "Envoyer un MP",
            "Masquer cet utilisateur",
            "Alerter (à venir)",
            "Supprimer ce message",
            "Édité le 02/01/2025 04:04:05",
            "Cité 2 fois dans le sujet",
        ).forEach { label ->
            compose.onNodeWithText(label).assertExists()
        }
        compose.onNodeWithText("Alerter (à venir)").assertIsNotEnabled()
    }

    private fun mount(post: Post, withAllActions: Boolean = false) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostMenuSheet(
                    post = post,
                    permalink = "https://forum.hardware.fr/message/42",
                    citedCount = post.citedCount ?: 0,
                    onDismiss = {},
                    onDelete = if (withAllActions) ({}) else null,
                    onEditFirstPost = if (withAllActions) ({}) else null,
                    onOpenProfile = if (withAllActions) ({}) else null,
                    onSendPrivateMessage = if (withAllActions) ({}) else null,
                    favoriteAction = if (withAllActions) {
                        PostFavoriteAction.ADD
                    } else {
                        PostFavoriteAction.HIDDEN
                    },
                    onFavoriteClick = {},
                    multiQuoteSelected = false,
                    onToggleMultiQuote = if (withAllActions) ({}) else null,
                    authorBlocked = false,
                    onToggleBlockAuthor = if (withAllActions) ({}) else null,
                )
            }
        }
    }

    private fun samplePost(): Post = Post(
        numreponse = 42,
        author = "Alice",
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(listOf(PostInline.Text("Premier paragraphe"))),
                PostBlock.Paragraph(listOf(PostInline.Text("Second paragraphe"))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        profileId = 123,
    )
}
