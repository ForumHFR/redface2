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
import androidx.compose.ui.test.assertIsSelected
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
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1051/#1117 — exact capabilities and data-driven metadata of the private-message menu. */
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
            callbacks = MenuCallbacks(onToggleBlockAuthor = {}),
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
    fun `a nonblank hidden message keeps copy visible but disabled`() {
        mount(
            message = sampleMessage(),
            authorBlocked = true,
            contentVisible = false,
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
            callbacks = MenuCallbacks(
                onOpenProfile = { profileOpens++ },
                onToggleBlockAuthor = {},
            ),
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
            callbacks = MenuCallbacks(onToggleBlockAuthor = { toggles++ }),
        )

        compose.onNodeWithText("Masquer cet utilisateur").assertDoesNotExist()
        compose.onNodeWithText("Ne plus masquer cet utilisateur")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun `multi quote entry exposes selected state and toggles the basket`() {
        var toggles = 0
        mount(
            message = sampleMessage(),
            callbacks = MenuCallbacks(onToggleMultiQuote = { toggles++ }),
        )

        compose.onNodeWithText("Ajouter à la citation multiple").assertDoesNotExist()
        compose.onNodeWithText("Retirer de la citation multiple")
            .assertIsDisplayed()
            .assertIsSelected()
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

    @Test
    fun `edit eligibility requires a live session and excludes both local storage identities`() {
        val editable = sampleMessage().copy(isEditable = true)
        assertTrue(canEditPrivateMessage(editable, "Alice", true, sampleThread()))
        assertFalse(canEditPrivateMessage(editable, null, true, sampleThread()))
        assertFalse(canEditPrivateMessage(editable, "Alice", false, sampleThread()))
        assertFalse(canEditPrivateMessage(editable, "Alice", true, sampleThread().copy(
            subject = fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository.STORAGE_SUBJECT_HASH)))
    }

    @Test
    fun `own message without toolbar uses the same canonical pseudo fallback as topics`() {
        val message = sampleMessage().copy(author = "  A\u200Blice   Du\u00A0Forum  ")

        assertFalse(message.isEditable)
        assertFalse(message.isOwnPost)
        assertTrue(canEditPrivateMessage(message, "alice du forum", true, sampleThread()))
        assertFalse(canEditPrivateMessage(message, null, true, sampleThread()))
        assertFalse(canEditPrivateMessage(message, "  ", true, sampleThread()))
        assertFalse(canEditPrivateMessage(message, "alice du forum", false, sampleThread()))
        assertFalse(canEditPrivateMessage(message, "alice du forum", true, sampleThread().copy(
            subject = fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository.STORAGE_SUBJECT_HASH)))
    }

    @Test
    fun `edit eligibility requires a replyable thread with or without a served link`() {
        val message = sampleMessage()
        val thread = sampleThread().copy(canReply = false)

        assertFalse(canEditPrivateMessage(message, "Alice", true, thread))
        assertFalse(canEditPrivateMessage(message.copy(isEditable = true), "Alice", true, thread))
    }

    @Test
    fun `previous page recap is excluded from the fallback even when quote ref is missing`() {
        for (ref in listOf(0, null)) {
            val recap = sampleMessage().copy(quoteRef = ref)
            val current = sampleMessage().copy(numreponse = 43)
            val thread = sampleThread().copy(page = 2, totalPages = 2, messages = listOf(recap, current))

            assertFalse(canEditPrivateMessage(recap, "Alice", true, thread))
            assertTrue(canEditPrivateMessage(current, "Alice", true, thread))
            assertTrue(canEditPrivateMessage(recap.copy(isEditable = true), "Alice", true, thread))
        }
    }

    @Test
    fun `similar pseudo and a quote from the account never grant edit permission`() {
        // This message quotes Alice, but its author is a different participant.
        val message = richMessage().copy(author = "Alice_", isOwnPost = true)

        assertFalse(canEditPrivateMessage(message, "Alice", true, sampleThread()))
        assertFalse(canEditPrivateMessage(sampleMessage().copy(author = "Alïce"), "Alice", true, sampleThread()))
    }

    @Test
    fun `own message without toolbar opens the editor after dismissing the menu`() {
        var edits = 0
        mount(
            message = sampleMessage(),
            connectedPseudo = "  aLiCe  ",
            callbacks = MenuCallbacks(onEdit = { edits++ }),
        )

        compose.onNodeWithText("Modifier").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 2_000) { edits == 1 }
    }

    @Test
    fun `editable message opens the editor after dismissing the menu`() {
        var edits = 0
        mount(
            message = sampleMessage().copy(isEditable = true, isOwnPost = true),
            callbacks = MenuCallbacks(onEdit = { edits++ }),
        )
        compose.onNodeWithText("Modifier").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 2_000) { edits == 1 }
    }

    @Test
    fun `another message never exposes edit even if a callback is supplied`() {
        mount(richMessage().copy(author = "Alice_"), connectedPseudo = "Alice", callbacks = MenuCallbacks(onEdit = {}))
        compose.onNodeWithText("Modifier").assertDoesNotExist()
    }

    private fun mount(
        message: Post,
        authorBlocked: Boolean = false,
        contentVisible: Boolean = true,
        callbacks: MenuCallbacks = MenuCallbacks(),
        connectedPseudo: String? = null,
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                MessageMenuSheet(
                    message = message,
                    authorBlocked = authorBlocked,
                    contentVisible = contentVisible,
                    onDismiss = {},
                    onOpenProfile = callbacks.onOpenProfile,
                    multiQuoteSelected = callbacks.onToggleMultiQuote != null,
                    onToggleMultiQuote = callbacks.onToggleMultiQuote,
                    onToggleBlockAuthor = callbacks.onToggleBlockAuthor,
                    onEdit = callbacks.onEdit,
                    connectedPseudo = connectedPseudo,
                )
            }
        }
    }

    private data class MenuCallbacks(
        val onOpenProfile: (() -> Unit)? = null,
        val onToggleMultiQuote: (() -> Unit)? = null,
        val onToggleBlockAuthor: (() -> Unit)? = null,
        val onEdit: (() -> Unit)? = null,
    )

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

    private fun sampleThread(): PrivateMessageThread = PrivateMessageThread(
        threadId = 1,
        subject = "Subject",
        correspondent = "Bob",
        messages = listOf(sampleMessage()),
        page = 1,
        totalPages = 1,
        canReply = true,
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
