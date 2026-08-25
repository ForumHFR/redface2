package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1041 — characterization of the complete, state-hoisted private-thread surface before the shared
 * reading-card refactor. These assertions pin the CURRENT MP composition: subject/correspondent
 * chrome, one ordered card per message, top-bar picker and bottom page-FAB cluster, and the anonymous
 * placeholder. #1050 adds the mounted full-width sequence, hot signature presentation and internal
 * LazyListState anchor proofs without weakening those default-path assertions. #509/#1050 adds the
 * blacklist placeholder, page-local reveal and blocked-quote provider contracts.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class PrivateMessageThreadContentTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `content renders the header ordered message list page pill and FAB cluster`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet caracterise",
                    correspondent = "Correspondant",
                    messages = listOf(
                        message(101, "Alpha", "Premier message"),
                        message(102, "Beta", "Deuxieme message"),
                        message(103, "Gamma", "Troisieme message"),
                    ),
                    page = 2,
                    totalPages = 4,
                    canReply = true,
                ),
            ),
            page = 2,
            totalPages = 4,
        )

        compose.onNodeWithText("Sujet caracterise").assertIsDisplayed()
        compose.onNodeWithText("Correspondant").assertIsDisplayed()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(3)
        val authorTops = listOf("Alpha", "Beta", "Gamma").map { author ->
            compose.onNode(heading.and(hasText(author)), useUnmergedTree = true)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        }
        assertTrue(
            "messages must stay oldest-first in the LazyColumn",
            authorTops.zipWithNext().all { (a, b) -> a < b },
        )

        listOf("Premier message", "Deuxieme message", "Troisieme message").forEach { body ->
            compose.onNodeWithText(body).assertIsDisplayed()
        }

        compose.onNodeWithText("Page 2 / 4").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page précédente").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Page suivante").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Précédent").assertDoesNotExist()
        compose.onNodeWithText("Suivant").assertDoesNotExist()
    }

    @Test
    fun `historical page FAB preference hides only page controls`() {
        mountState(
            state = contentState(
                messages = listOf(message(101, "Alpha", "Corps du MP")),
                page = 2,
                totalPages = 4,
                canReply = true,
            ).copy(showPageFabs = false),
        )

        compose.onNodeWithContentDescription("Page précédente").assertDoesNotExist()
        compose.onNodeWithContentDescription("Page suivante").assertDoesNotExist()
        // Assert the actual affordance rather than ExtendedFAB's animated Text leaf: the leaf can
        // exist with transient zero bounds while the complete clickable surface is already placed.
        compose.onNode(
            hasClickAction() and hasAnyDescendant(hasText("Répondre")),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("Page 2 / 4").assertIsDisplayed()
    }

    @Test
    fun `page chrome refuses selection while a landing owns the list`() {
        var selectionCount = 0
        val state = contentState(
            messages = listOf(message(201, "Alpha", "Page deux")),
            page = 2,
            totalPages = 4,
        ).copy(
            pageLanding = PrivateMessagePageLanding.Top(
                generation = 1,
                account = "xaat",
                page = 2,
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onSelectPage = { _, _ -> selectionCount += 1 },
                    ),
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Page précédente").performClick()

        assertEquals(0, selectionCount)
    }

    @Test
    fun `page FAB taps and long presses each dispatch exactly one bounded target with an anchor`() {
        val selections = mutableListOf<Pair<Int, PrivateMessageScrollAnchor?>>()
        mountState(
            state = contentState(
                messages = listOf(message(201, "Alpha", "Page deux")),
                page = 2,
                totalPages = 4,
            ),
            callbacks = NO_OP_CALLBACKS.copy(
                onSelectPage = { page, anchor -> selections += page to anchor },
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Page précédente").performClick()
        compose.onNodeWithContentDescription("Page suivante").performClick()
        compose.onNodeWithContentDescription("Page précédente").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Page suivante").performTouchInput { longClick() }

        assertEquals(listOf(1, 3, 1, 4), selections.map { it.first })
        assertTrue("every chrome action must capture the aligned departure anchor", selections.all {
            it.second != null
        })
    }

    @Test
    fun `page picker ignores out of bounds and current targets then dispatches one valid target`() {
        val selectedPages = mutableListOf<Int>()
        mountState(
            state = contentState(
                messages = listOf(message(201, "Alpha", "Page deux")),
                page = 2,
                totalPages = 4,
            ),
            callbacks = NO_OP_CALLBACKS.copy(
                onSelectPage = { page, _ -> selectedPages += page },
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Page 2 sur 4").performClick()
        val jumpField = compose.onNode(hasSetTextAction())
        jumpField.performTextReplacement("9")
        compose.onNodeWithText("Y aller").performClick()
        jumpField.performTextReplacement("2")
        compose.onNodeWithText("Y aller").performClick()
        assertEquals(emptyList<Int>(), selectedPages)

        jumpField.performTextReplacement("3")
        compose.onNodeWithText("Y aller").performClick()
        compose.waitForIdle()

        assertEquals(listOf(3), selectedPages)
        compose.onNodeWithText("Aller à une page").assertDoesNotExist()
    }

    @Test
    fun `an in-flight switch disarms the pill picker and both page FABs`() {
        val selectedPages = mutableListOf<Int>()
        val state = mutableStateOf(
            contentState(
                messages = listOf(message(101, "Alice", "Corps du MP")),
                page = 2,
                totalPages = 4,
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onSelectPage = { page, _ -> selectedPages += page },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Page 2 sur 4").performClick()
        compose.runOnIdle { state.value = state.value.copy(isRefreshing = true) }
        compose.waitForIdle()

        compose.onNodeWithText("Précédent").assertIsNotEnabled()
        compose.onNodeWithText("Suivant").assertIsNotEnabled()
        compose.onNodeWithText("Y aller").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page 2 sur 4, actualisation en cours")
            .assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page précédente").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Page suivante").assertIsNotEnabled()
        assertEquals(emptyList<Int>(), selectedPages)
    }

    // Native text metrics keep the observable single-line and complete-pill contracts reliable.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `at 360 dp and large font the complete MultiMP subtitle fits left of the complete page pill`() {
        val subjectText = "Sujet très long qui doit rester sur la première ligne"
        val subtitleText = "Interlocuteurs multiples"
        val pagePosition = "Page 987 / 1234"
        mountState(state = multiRecipientState(subjectText), fontScale = 2f)

        val subject = compose.onNodeWithText(subjectText).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val subtitle = compose.onNodeWithText(subtitleText).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText(pagePosition).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue("the subject must remain on the first line", subject.bottom <= subtitle.top)
        assertTrue("the weighted subtitle must stay left of the page pill", subtitle.right <= pill.left)

        val subtitleLayout = textLayout(subtitleText)
        assertEquals(1, subtitleLayout.lineCount)

        // Coverage gap for the follow-up issue: overflow must ellipsize the subtitle, never the pill,
        // but isLineEllipsized reports false here with 139 px available for 183 px requested.

        val pillLayout = textLayout(pagePosition)
        assertEquals(1, pillLayout.lineCount)
        assertTrue("the page pill must not ellipsize", !pillLayout.isLineEllipsized(0))
        assertEquals(pagePosition.length, pillLayout.getLineEnd(0, visibleEnd = true))
    }

    @Test
    fun `message ellipsis opens the decided MP menu without a card long click`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Menu MP",
                    correspondent = "Correspondant",
                    messages = listOf(message(101, "Alice", "Texte à copier")),
                    page = 1,
                    totalPages = 1,
                    canReply = false,
                ),
            ),
            page = 1,
            totalPages = 1,
        )

        compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick),
            useUnmergedTree = true,
        ).assertDoesNotExist()
        compose.onNodeWithContentDescription("Options du message")
            .assertIsDisplayed()
            .performClick()

        compose.onNodeWithText("Copier le texte").assertIsDisplayed()
        compose.onNodeWithText("Masquer cet utilisateur").assertIsDisplayed()
        compose.onNodeWithText("Copier le lien de ce post").assertDoesNotExist()
        compose.onNodeWithText("Ouvrir dans le navigateur").assertDoesNotExist()
        compose.onNodeWithText("Citer").assertDoesNotExist()

        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun `visible message with server ref exposes both footer quote actions from one gate`() {
        var quotedMessage: Post? = null
        var toggledSelection: QuoteSelection? = null
        val state = contentState(
            messages = listOf(message(101, "Alice", "Message citable").copy(quoteRef = 4)),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onQuote = { quotedMessage = it },
                        onToggleMultiQuote = { toggledSelection = it },
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Ajouter à la citation multiple")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Citer").assertIsDisplayed().performClick()

        assertEquals(101, quotedMessage?.numreponse)
        assertEquals(4, quotedMessage?.quoteRef)
        assertEquals(QuoteLocator(page = 1, numreponse = 101, ref = 4), toggledSelection?.locator)
    }

    @Test
    fun `selected direct multi quote action exposes state and removes the exact locator`() {
        val selection = QuoteSelection(
            locator = QuoteLocator(page = 3, numreponse = 101, ref = 4),
            author = "Alice",
            excerpt = "Message citable",
        )
        var toggledSelection: QuoteSelection? = null
        val state = contentState(
            messages = listOf(message(101, "Alice", "Message citable").copy(quoteRef = 4)),
            page = 3,
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onQuote = {},
                        onToggleMultiQuote = { toggledSelection = it },
                    ),
                    presentation = PrivateMessageThreadPresentation(
                        multiQuoteSelections = listOf(selection),
                    ),
                )
            }
        }

        compose.onNodeWithContentDescription("Retirer de la citation multiple")
            .assertIsDisplayed()
            .assertIsSelected()
            .performClick()

        assertEquals(selection.locator, toggledSelection?.locator)
    }

    @Test
    fun `parsed MP quote header reaches the cited-message host callback`() {
        var citedTarget: Pair<Int, Int>? = null
        val quotingMessage = message(102, "Bob", "Réponse").copy(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Quote(
                        author = "Alice",
                        numreponse = 101,
                        page = 3,
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(PostInline.Text("Message cité")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val state = contentState(messages = listOf(quotingMessage))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onGoToCitedPost = { page, numreponse, _ ->
                            citedTarget = page to numreponse
                        },
                    ),
                )
            }
        }

        compose.onNodeWithText("Citation de Alice").performClick()

        assertEquals(3 to 101, citedTarget)
    }

    @Test
    fun `typed page landing restores anchor and cited message without cache-network replay`() {
        val messages = (1..40).map { index -> message(index, "Auteur $index", "Message $index") }
        val renderedPage = mutableStateOf(1)
        val isRefreshing = mutableStateOf(true)
        val landing = mutableStateOf<PrivateMessagePageLanding?>(null)
        val consumed = mutableListOf<PrivateMessagePageLanding>()
        lateinit var listState: LazyListState
        compose.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 10)
            val mode = contentState(messages = messages, page = renderedPage.value).mode
            val thread = (mode as PrivateMessageThreadUiState.Mode.Content).thread
            val alignment = androidx.compose.runtime.remember { PrivateMessageListAlignment() }
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessagePageLandingEffect(
                    context = PrivateMessageLandingRenderContext(
                        listState = listState,
                        thread = thread,
                        connectedPseudo = "xaat",
                        isRefreshing = isRefreshing.value,
                        alignment = alignment,
                    ),
                    presentation = PrivateMessageLandingPresentation(
                        landing = landing.value,
                        onConsumed = { effect ->
                            consumed += effect
                        },
                    ),
                )
                LazyColumn(state = listState) {
                    items(messages, key = { message -> message.numreponse }) { message ->
                        Text(
                            text = "Message ${message.numreponse}",
                            modifier = Modifier.fillMaxWidth().height(100.dp),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        assertEquals("first render keeps the restored position", 10, listState.firstVisibleItemIndex)

        val anchor = PrivateMessagePageLanding.Anchor(
            generation = 2,
            account = "xaat",
            page = 2,
            anchor = PrivateMessageScrollAnchor(index = 12, offset = 17),
        )
        compose.runOnIdle {
            renderedPage.value = 2
            landing.value = anchor
        }
        compose.waitForIdle()
        assertEquals(12, listState.firstVisibleItemIndex)
        assertEquals(17, listState.firstVisibleItemScrollOffset)
        assertEquals(listOf(anchor), consumed)

        // Network revalidation retains the same landing value. Anchor/top effects deliberately do
        // not key on isRefreshing, so this cannot produce a second scroll/acknowledgement.
        compose.runOnIdle { isRefreshing.value = false }
        compose.waitForIdle()
        assertEquals(listOf(anchor), consumed)

        val target = PrivateMessagePageLanding.CitedMessage(
            generation = 3,
            account = "xaat",
            page = 3,
            numreponse = 21,
        )
        compose.runOnIdle {
            renderedPage.value = 3
            landing.value = target
        }
        compose.waitForIdle()

        assertEquals("the cited message, not item 0, owns the landing", 20, listState.firstVisibleItemIndex)
        assertEquals(listOf(anchor, target), consumed)

        compose.runOnIdle {
            landing.value = null
            renderedPage.value = 4
        }
        compose.waitForIdle()
        assertEquals("a later ordinary page change still lands at the top", 0, listState.firstVisibleItemIndex)
    }

    @Test
    fun `missing ref and hidden message expose neither footer quote action`() {
        val state = contentState(
            messages = listOf(
                message(101, "Alice", "Visible sans rang"),
                message(102, "Bob", "Masqué avec rang").copy(quoteRef = 2),
            ),
            hiddenNumreponses = setOf(102),
            blockedQuoteAuthors = setOf("bob"),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(onQuote = { _ -> }),
                )
            }
        }

        compose.onNodeWithText("Visible sans rang").assertIsDisplayed()
        compose.onNodeWithText("Post de Bob masqué").assertIsDisplayed()
        compose.onNodeWithText("Citer").assertDoesNotExist()
        compose.onNodeWithText("+ Citer").assertDoesNotExist()
        compose.onNodeWithText("✓ Cité").assertDoesNotExist()
    }

    @Test
    fun `read only thread exposes neither footer quote action despite a positive ref`() {
        val state = contentState(
            messages = listOf(message(101, "Alice", "Lecture seule").copy(quoteRef = 4)),
            canReply = false,
        )
        mountState(
            state = state,
            callbacks = NO_OP_CALLBACKS.copy(onQuote = {}, onToggleMultiQuote = {}),
        )

        compose.onNodeWithText("Citer").assertDoesNotExist()
        compose.onNodeWithText("+ Citer").assertDoesNotExist()
    }

    @Test
    fun `hidden placeholder keeps explicit menu access but no quote affordance`() {
        val state = contentState(
            messages = listOf(message(102, "Bob", "Masqué").copy(quoteRef = 2)),
            hiddenNumreponses = setOf(102),
            blockedQuoteAuthors = setOf("bob"),
            canReply = true,
        )
        mountState(
            state = state,
            callbacks = NO_OP_CALLBACKS.copy(onQuote = {}, onToggleMultiQuote = {}),
        )

        compose.onNodeWithText("Post de Bob masqué").assertIsDisplayed()
        compose.onNodeWithContentDescription("Options du message")
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithText("Ne plus masquer cet utilisateur").assertIsDisplayed()
        compose.onNodeWithText("Ajouter à la citation multiple").assertDoesNotExist()
        compose.onNodeWithText("Citer").assertDoesNotExist()
    }

    @Test
    fun `a selected message becoming hidden is reported to the app basket`() {
        var removed = emptySet<Int>()
        val selected = listOf(
            QuoteSelection(
                locator = QuoteLocator(page = 1, numreponse = 102, ref = 2),
                author = "Bob",
                excerpt = "Masqué",
            ),
            QuoteSelection(
                locator = QuoteLocator(page = 8, numreponse = 999, ref = 4),
                author = "BOB",
                excerpt = "Sélectionné sur une autre page",
            ),
        )
        val state = contentState(
            messages = listOf(message(102, "Bob", "Masqué").copy(quoteRef = 2)),
            hiddenNumreponses = setOf(102),
            blockedQuoteAuthors = setOf("bob"),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(onRemoveMultiQuotes = { removed = it }),
                    presentation = PrivateMessageThreadPresentation(
                        multiQuoteSelections = selected,
                    ),
                )
            }
        }

        compose.waitUntil { removed == setOf(102, 999) }
    }

    @Test
    fun `an explicitly revealed blocked message stays outside both quote affordances`() {
        val state = contentState(
            messages = listOf(message(102, "Bob", "Révélé mais bloqué").copy(quoteRef = 2)),
            hiddenNumreponses = setOf(102),
            blockedQuoteAuthors = setOf("bob"),
            canReply = true,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onQuote = {},
                        onToggleMultiQuote = {},
                    ),
                )
            }
        }

        compose.onNodeWithText("Afficher").performClick()
        compose.onNodeWithText("Révélé mais bloqué").assertIsDisplayed()
        compose.onNodeWithText("Citer").assertDoesNotExist()
        compose.onNodeWithText("+ Citer").assertDoesNotExist()
        compose.onNodeWithContentDescription("Options du message").performClick()
        compose.onNodeWithText("Ajouter à la citation multiple").assertDoesNotExist()
        compose.onNodeWithText("Ne plus masquer cet utilisateur").assertIsDisplayed()
    }

    @Test
    fun `requires login keeps private content out of the composition`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.RequiresLogin,
            page = 1,
            totalPages = 1,
        )

        compose.onNodeWithText("Conversation privée").assertIsDisplayed()
        compose.onNodeWithText("Connexion requise").assertIsDisplayed()
        compose.onNodeWithText("Connectez-vous depuis le menu compte pour lire vos messages privés.")
            .assertIsDisplayed()
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Page 1 / 1").assertDoesNotExist()
        compose.onNodeWithText("Répondre").assertDoesNotExist()
    }

    @Test
    fun `full width keeps one heading per message and no dangling hairline after the last message`() {
        setContent(
            mode = PrivateMessageThreadUiState.Mode.Content(
                PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Pleine largeur",
                    correspondent = "Correspondant",
                    messages = listOf(
                        message(101, "Alpha", "Premier message"),
                        message(102, "Beta", "Deuxieme message"),
                        message(103, "Gamma", "Troisieme message"),
                    ),
                    page = 2,
                    totalPages = 4,
                    canReply = false,
                ),
            ),
            page = 2,
            totalPages = 4,
            fullWidthPosts = true,
        )

        // Three messages produce exactly two message→message boundaries. The third is now the last
        // list item and must not leave a dangling boundary below it.
        val dividers = compose.onAllNodesWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertCountEquals(2)
            .fetchSemanticsNodes()
        val finalMessageTop = compose.onNodeWithText("Troisieme message").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "the final message must follow the last message-to-message divider",
            dividers.maxOf { it.boundsInRoot.bottom } <= finalMessageTop,
        )
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun `signature preference renders parsed signature without replacing the message`() {
        val state = mutableStateOf(
            contentState(
                messages = listOf(
                    message(
                        numreponse = 101,
                        author = "Alpha",
                        body = "Corps stable",
                        signature = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(PostInline.Text("Signature MP simulée")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Signature MP simulée").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(showSignatures = true) }

        compose.onNodeWithText("Corps stable").assertIsDisplayed()
        compose.onNodeWithText("Signature MP simulée").assertIsDisplayed()
    }

    @Test
    fun `blacklist keeps one placeholder per message in one-to-one and DT threads`() {
        val messages = listOf(
            message(101, "Alice", "Secret Alice"),
            message(102, "Bob", "Message visible"),
        )
        val state = mutableStateOf(
            contentState(
                messages = messages,
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                isMultiRecipient = false,
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    // Deliberately false: masking must not depend on this ephemeral inbox hint.
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret Alice").assertDoesNotExist()
        compose.onNodeWithText("Message visible").assertIsDisplayed()
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(2)

        compose.runOnIdle {
            state.value = contentState(
                messages = messages,
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                isMultiRecipient = true,
            )
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret Alice").assertDoesNotExist()
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun `revealing a hidden message lasts only until another page lands`() {
        val state = mutableStateOf(
            contentState(
                messages = listOf(message(101, "Alice", "Secret page 1")),
                hiddenNumreponses = setOf(101),
                blockedQuoteAuthors = setOf("alice"),
                page = 1,
                totalPages = 2,
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Secret page 1").assertDoesNotExist()
        compose.onNodeWithText("Afficher").performClick()
        compose.onNodeWithText("Secret page 1").assertIsDisplayed()

        compose.runOnIdle {
            state.value = contentState(
                messages = listOf(message(201, "Alice", "Secret page 2")),
                hiddenNumreponses = setOf(201),
                blockedQuoteAuthors = setOf("alice"),
                page = 2,
                totalPages = 2,
            )
        }

        compose.onNodeWithText("Post de Alice masqué").assertIsDisplayed()
        compose.onNodeWithText("Secret page 2").assertDoesNotExist()
    }

    @Test
    fun `blocked quote authors are masked inside a visible MP message`() {
        val quotedBody = "Fuite par citation"
        val quotingMessage = message(102, "Bob", "Introduction").copy(
            content = PostContent(
                blocks = listOf(
                    PostBlock.Paragraph(inlines = listOf(PostInline.Text("Introduction"))),
                    PostBlock.Quote(
                        author = "Alice",
                        numreponse = 101,
                        page = 1,
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(inlines = listOf(PostInline.Text(quotedBody))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val state = contentState(
            messages = listOf(quotingMessage),
            hiddenNumreponses = emptySet(),
            blockedQuoteAuthors = setOf("alice"),
        )

        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNodeWithText("Introduction").assertIsDisplayed()
        compose.onNodeWithText("Citation de Alice masquée").assertIsDisplayed()
        compose.onNodeWithText(quotedBody).assertDoesNotExist()
    }

    @Test
    fun `full width toggle keeps the internal lazy list reading anchor`() {
        val state = mutableStateOf(
            contentState(
                messages = (1..20).map { index ->
                    message(index, "Auteur $index", "Message $index")
                },
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state.value,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        compose.onNode(hasScrollAction())
            .performSemanticsAction(SemanticsActions.ScrollToIndex) { scroll -> scroll(TARGET_INDEX) }
        compose.waitForIdle()
        val anchor = compose.onNodeWithText(TARGET_AUTHOR)
        anchor.assertIsDisplayed()
        val topBefore = anchor.fetchSemanticsNode().boundsInRoot.top

        compose.runOnIdle { state.value = state.value.copy(fullWidthPosts = true) }
        compose.waitForIdle()

        // At least one flat divider proves the new geometry reached the visible list. If the
        // internal rememberLazyListState had been recreated, TARGET_AUTHOR would no longer be the
        // displayed anchor: the first message would replace it at the top.
        assertTrue(
            compose.onAllNodesWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        anchor.assertIsDisplayed()
        assertEquals(
            "the same keyed message must stay pinned after the render-only preference flip",
            topBefore,
            anchor.fetchSemanticsNode().boundsInRoot.top,
            ANCHOR_TOLERANCE_PX,
        )
    }

    @Test
    fun `pinch zoom disables the pull gesture until reset`() {
        var refreshCount = 0
        val state = contentState(messages = listOf(message(1, "Alice", "Corps du MP")))
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(onRefresh = { refreshCount++ }),
                )
            }
        }

        pullDownThread()
        assertEquals("the rest-state pull must reach the refresh callback", 1, refreshCount)

        pinchOutThread()
        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).assertIsDisplayed()
        pullDownThread()

        assertEquals(
            "the low-level disabled pull modifier must not arm or refresh while zoomed",
            1,
            refreshCount,
        )
        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `zoom resets when the feature owned conversation page key changes`() {
        val renderedPage = mutableStateOf(1)
        compose.setContent {
            val state = contentState(
                messages = listOf(message(renderedPage.value, "Alice", "Page ${renderedPage.value}")),
                page = renderedPage.value,
                totalPages = 2,
            )
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS,
                )
            }
        }

        pinchOutThread()
        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).assertIsDisplayed()

        compose.runOnIdle { renderedPage.value = 2 }
        compose.waitForIdle()

        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).assertDoesNotExist()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
    }

    @Test
    fun `thread page swipe is suspended after the pinch engages`() {
        val selectedPages = mutableListOf<Int>()
        val state = contentState(
            messages = listOf(message(1, "Alice", "Corps du MP")),
            page = 1,
            totalPages = 3,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PrivateMessageThreadContent(
                    state = state,
                    isMultiRecipientHint = false,
                    callbacks = NO_OP_CALLBACKS.copy(
                        onSelectPage = { page, _ -> selectedPages += page },
                    ),
                )
            }
        }

        swipeThreadLeft()
        assertEquals("the shared magnifier must remain transparent at 1x", listOf(2), selectedPages)

        pinchOutThread()
        swipeThreadLeft()

        assertEquals("a zoomed MP must not dispatch a page swipe", listOf(2), selectedPages)
    }

    @Test
    fun `direct multi quote target is inert while the reader is zoomed`() {
        var toggles = 0
        val state = contentState(
            messages = listOf(message(1, "Alice", "Corps du MP").copy(quoteRef = 1)),
            canReply = true,
        )
        mountState(
            state = state,
            callbacks = NO_OP_CALLBACKS.copy(
                onQuote = {},
                onToggleMultiQuote = { toggles++ },
            ),
        )

        compose.onNodeWithContentDescription("Ajouter à la citation multiple")
            .performTouchInput { click() }
        assertEquals("the target must work at rest", 1, toggles)

        pinchOutThread()
        compose.onNodeWithContentDescription(ZOOM_RESET_DESCRIPTION).assertIsDisplayed()
        compose.onNodeWithContentDescription("Ajouter à la citation multiple")
            .performTouchInput { click() }

        assertEquals("the zoom modifier must consume down before the child target", 1, toggles)
    }

    private fun pinchOutThread() {
        compose.onNodeWithTag(PRIVATE_MESSAGE_THREAD_READER_TAG).performTouchInput {
            down(0, center - Offset(0f, 150f))
            down(1, center + Offset(0f, 150f))
            repeat(10) { index ->
                val halfGap = 150f + 25f * (index + 1)
                updatePointerTo(0, center - Offset(0f, halfGap))
                updatePointerTo(1, center + Offset(0f, halfGap))
                move()
            }
            up(0)
            up(1)
        }
        compose.waitForIdle()
    }

    private fun pullDownThread() {
        compose.onNodeWithTag(PRIVATE_MESSAGE_THREAD_READER_TAG).performTouchInput {
            down(0, center - Offset(0f, 300f))
            repeat(8) { moveBy(0, Offset(0f, 100f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private fun swipeThreadLeft() {
        compose.onNodeWithTag(PRIVATE_MESSAGE_THREAD_READER_TAG).performTouchInput {
            down(0, center)
            repeat(8) { moveBy(0, Offset(-60f, 0f)) }
            up(0)
        }
        compose.waitForIdle()
    }

    private fun setContent(
        mode: PrivateMessageThreadUiState.Mode,
        page: Int,
        totalPages: Int,
        fullWidthPosts: Boolean = false,
    ) {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = page)
        val state = PrivateMessageThreadUiState(
            request = request,
            mode = mode,
            page = page,
            totalPages = totalPages,
            fullWidthPosts = fullWidthPosts,
        )
        mountState(state)
    }

    private fun mountState(
        state: PrivateMessageThreadUiState,
        callbacks: PrivateMessageThreadCallbacks = NO_OP_CALLBACKS,
        fontScale: Float = 1f,
    ) {
        compose.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
            ) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    PrivateMessageThreadContent(
                        state = state,
                        isMultiRecipientHint = false,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }

    private fun multiRecipientState(subject: String): PrivateMessageThreadUiState {
        val base = contentState(
            messages = listOf(message(101, "Alice", "Corps du MP")),
            page = 987,
            totalPages = 1234,
            isMultiRecipient = true,
        )
        val content = base.mode as PrivateMessageThreadUiState.Mode.Content
        return base.copy(
            mode = content.copy(
                thread = content.thread.copy(
                    subject = subject,
                    correspondent = "Ce correspondant ne doit pas remplacer le libellé MultiMP",
                ),
            ),
        )
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val readLayout = requireNotNull(
            compose.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue("the Text layout for '$text' must be readable", readLayout(layouts))
        return layouts.single()
    }

    @Suppress("LongParameterList") // Test state factory: every argument controls one independent contract.
    private fun contentState(
        messages: List<Post>,
        hiddenNumreponses: Set<Int> = emptySet(),
        blockedQuoteAuthors: Set<String> = emptySet(),
        page: Int = 1,
        totalPages: Int = 1,
        isMultiRecipient: Boolean = false,
        canReply: Boolean = false,
    ): PrivateMessageThreadUiState {
        val request = PrivateMessageThreadRequest(threadId = THREAD_ID, page = page)
        return PrivateMessageThreadUiState(
            request = request,
            mode = PrivateMessageThreadUiState.Mode.Content(
                thread = PrivateMessageThread(
                    threadId = THREAD_ID,
                    subject = "Sujet",
                    correspondent = "Correspondant",
                    messages = messages,
                    page = page,
                    totalPages = totalPages,
                    canReply = canReply,
                    isMultiRecipient = isMultiRecipient,
                ),
                hiddenNumreponses = hiddenNumreponses,
                blockedQuoteAuthors = blockedQuoteAuthors,
            ),
            page = page,
            totalPages = totalPages,
        )
    }

    private fun message(
        numreponse: Int,
        author: String,
        body: String,
        signature: PostContent? = null,
    ): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.EPOCH.plusSeconds(numreponse.toLong()),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(body))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        signature = signature,
    )

    private companion object {
        const val THREAD_ID = 42
        const val TARGET_INDEX = 10
        const val TARGET_AUTHOR = "Auteur 11"
        const val ANCHOR_TOLERANCE_PX = 0.5f
        const val ZOOM_RESET_DESCRIPTION = "Revenir au zoom normal"

        val NO_OP_CALLBACKS = PrivateMessageThreadCallbacks(
            onBack = {},
            onReply = {},
            onRetry = {},
            onRefresh = {},
            onSelectPage = { _, _ -> },
            onOpenRoster = {},
            onDismissRoster = {},
            onRetryRoster = {},
            onManageRecipients = {},
        )
    }
}
