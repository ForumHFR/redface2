package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.domain.ego.deriveEgoCanonicalPseudo
import fr.forumhfr.redface2.core.domain.ego.isEgoPost
import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.HiddenPostCard
import fr.forumhfr.redface2.core.ui.post.POST_CARD_SHELL_DIVIDER_TAG
import fr.forumhfr.redface2.core.ui.post.PostCardShellContainerColorKey
import fr.forumhfr.redface2.core.ui.post.PostIdentityBandContainerColorKey
import fr.forumhfr.redface2.core.ui.post.PostIdentityBandContentColorKey
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicEgoHighlightRenderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `EgoPost colours an inset card while the normal identity band stays intact`() {
        setCard(egoPostHighlighted = true, highlighted = false, flat = false)

        assertShellColor(EGO_POST_LIGHT)
        assertIdentityBandColor(RedfaceLightColorScheme.secondaryContainer)
        // C6 — a normal band forwards contentColorFor(secondaryContainer). This theme reuses #FFDAD9
        // for BOTH primaryContainer and secondaryContainer, so M3 contentColorFor matches the
        // earlier-listed primaryContainer role and resolves onPrimaryContainer, not onSecondaryContainer.
        assertIdentityBandContentColor(RedfaceLightColorScheme.onPrimaryContainer)
        assertStateCount(OWN_POST_STATE, 1)
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `EgoPost colours a flat card while the targeted identity band and hairline stay intact`() {
        setCard(egoPostHighlighted = true, highlighted = true, flat = true)

        assertShellColor(EGO_POST_LIGHT)
        assertIdentityBandColor(RedfaceLightColorScheme.tertiaryContainer)
        assertIdentityBandContentColor(RedfaceLightColorScheme.onTertiaryContainer)
        assertStateCount(OWN_POST_STATE, 1)
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `moderation post renders an RF1 red body and darker identity band`() {
        val post = samplePost(isModerationPost = true).copy(editedAt = Instant.EPOCH)
        setCard(post = post)

        assertShellColor(MODERATION_BODY_LIGHT)
        assertIdentityBandColor(MODERATION_HEADER_LIGHT)
        assertIdentityBandContentColor(Color.White)
        assertTextColor(post.date.asTopicDate(), Color.White)
        assertTextColor("· édité", Color.White)
        assertTextColor("⋯", Color.White)
        assertStateCount(MODERATION_STATE, 1)
        assertStateCount(OWN_POST_STATE, 0)
    }

    @Test
    fun `the transient scroll anchor keeps its band and content above moderation`() {
        val post = samplePost(isModerationPost = true)
        setCard(post = post, highlighted = true)

        // The card stays red (persistent marker); both transient anchor band roles win above it.
        assertShellColor(MODERATION_BODY_LIGHT)
        assertIdentityBandColor(RedfaceLightColorScheme.tertiaryContainer)
        assertIdentityBandContentColor(RedfaceLightColorScheme.onTertiaryContainer)
        assertTextColor(post.date.asTopicDate(), RedfaceLightColorScheme.onTertiaryContainer)
        assertStateCount(MODERATION_STATE, 1)
    }

    @Test
    fun `an EgoPost that is also moderation stays blue on the card and neutral on the band`() {
        setCard(post = samplePost(isModerationPost = true), egoPostHighlighted = true)

        assertShellColor(EGO_POST_LIGHT)
        assertIdentityBandColor(RedfaceLightColorScheme.secondaryContainer)
        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(MODERATION_STATE, 0)
    }

    @Test
    fun `EgoPost and EgoQuote accumulate on an own post with an auto-citation`() {
        setCard(
            post = samplePost(content = autoQuoteContent()),
            egoPostHighlighted = true,
            egoQuoteCanonicalPseudo = EGO_CANONICAL,
        )

        assertShellColor(EGO_POST_LIGHT)
        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 1)
    }

    @Test
    fun `staff pill coexists with the scroll anchor and EgoPost markers`() {
        setCard(
            egoPostHighlighted = true,
            highlighted = true,
            staffByPseudo = mapOf(EGO_CANONICAL to AuthorRole.ARCHITECT),
        )

        composeTestRule
            .onNodeWithContentDescription("Rôle : Architecte / Développeur principal")
            .assertExists()
        assertIdentityBandColor(RedfaceLightColorScheme.tertiaryContainer)
        assertStateCount(OWN_POST_STATE, 1)
    }

    @Test
    fun `moderation post suppresses the staff pill`() {
        setCard(
            post = samplePost(isModerationPost = true),
            staffByPseudo = mapOf(EGO_CANONICAL to AuthorRole.MODERATOR),
        )

        composeTestRule.onNodeWithContentDescription("Rôle : Modérateur").assertDoesNotExist()
        assertStateCount(MODERATION_STATE, 1)
    }

    @Test
    fun `both highlights off preserve the historical inset container`() {
        setCard(egoPostHighlighted = false, egoQuoteCanonicalPseudo = null, flat = false)
        assertShellColor(RedfaceLightColorScheme.surfaceContainer)
    }

    @Test
    fun `both highlights off preserve the historical flat container`() {
        setCard(egoPostHighlighted = false, egoQuoteCanonicalPseudo = null, flat = true)
        assertShellColor(Color.Transparent)
    }

    @Test
    fun `turning EgoQuote off keeps EgoPost on`() {
        setCard(
            post = samplePost(content = autoQuoteContent()),
            egoPostHighlighted = true,
            egoQuoteCanonicalPseudo = null,
        )

        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Test
    fun `turning EgoPost off keeps EgoQuote on`() {
        setCard(
            post = samplePost(content = autoQuoteContent()),
            egoPostHighlighted = false,
            egoQuoteCanonicalPseudo = EGO_CANONICAL,
        )

        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 1)
    }

    @Test
    fun `anonymous session keeps both markers off`() {
        val post = samplePost(content = autoQuoteContent())
        val canonical = deriveEgoCanonicalPseudo(
            enabled = true,
            isAuthenticated = false,
            connectedPseudo = EGO_PSEUDO,
        )

        setCard(
            post = post,
            egoPostHighlighted = isEgoPost(post = post, egoCanonicalPseudo = canonical),
            egoQuoteCanonicalPseudo = canonical,
        )

        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Test
    fun `session pseudo change updates both highlights without resetting the quote fold`() {
        val connectedPseudo = mutableStateOf<String?>(EGO_PSEUDO)
        val post = samplePost(content = longAutoQuoteContent())

        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val canonical = deriveEgoCanonicalPseudo(
                    enabled = true,
                    isAuthenticated = true,
                    connectedPseudo = connectedPseudo.value,
                )
                LazyColumn {
                    item(key = post.numreponse) {
                        TopicPostCard(
                            post = post,
                            citedCount = 0,
                            egoQuoteCanonicalPseudo = canonical,
                            egoPostHighlighted = isEgoPost(post, canonical),
                            onQuote = null,
                            onEdit = null,
                        )
                    }
                }
            }
        }

        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 1)
        composeTestRule.onNodeWithText("Déplier").performClick()
        composeTestRule.onNodeWithText("Replier").assertExists()

        composeTestRule.runOnIdle { connectedPseudo.value = "SomeoneElse" }

        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)
        assertShellColor(RedfaceLightColorScheme.surfaceContainer)
        composeTestRule.onNodeWithText("Replier").assertExists()

        composeTestRule.runOnIdle { connectedPseudo.value = EGO_PSEUDO }

        assertStateCount(OWN_POST_STATE, 1)
        assertStateCount(OWN_QUOTE_STATE, 1)
        assertShellColor(EGO_POST_LIGHT)
        composeTestRule.onNodeWithText("Replier").assertExists()
    }

    @Test
    fun `EgoQuote provider stays scoped to the post body and never reaches the signature`() {
        val signatureQuote = autoQuoteContent()

        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(signature = signatureQuote),
                    citedCount = 0,
                    showSignature = true,
                    egoQuoteCanonicalPseudo = EGO_CANONICAL,
                    onQuote = null,
                    onEdit = null,
                )
            }
        }

        composeTestRule.onNodeWithText("mon ancien message").assertExists()
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Test
    fun `a blacklisted own-author row renders the hidden card without an Ego marker`() {
        val post = samplePost()
        assertTrue(isHiddenPost(post, hidden = setOf(post.numreponse), revealed = emptySet()))

        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                HiddenPostCard(author = post.author, onReveal = {})
            }
        }

        composeTestRule.onNodeWithText("Post de $EGO_PSEUDO masqué").assertExists()
        assertStateCount(OWN_POST_STATE, 0)
        assertStateCount(OWN_QUOTE_STATE, 0)
    }

    @Suppress("LongParameterList") // Render harness: each argument controls one independent card knob, all defaulted.
    private fun setCard(
        post: Post = samplePost(),
        egoPostHighlighted: Boolean = false,
        egoQuoteCanonicalPseudo: String? = null,
        highlighted: Boolean = false,
        flat: Boolean = false,
        staffByPseudo: Map<String, AuthorRole> = emptyMap(),
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    TopicPostCard(
                        post = post,
                        staffByPseudo = staffByPseudo,
                        highlighted = highlighted,
                        citedCount = 0,
                        flat = flat,
                        egoQuoteCanonicalPseudo = egoQuoteCanonicalPseudo,
                        egoPostHighlighted = egoPostHighlighted,
                        onQuote = null,
                        onEdit = null,
                    )
                }
            }
        }
    }

    private fun assertShellColor(expected: Color) {
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(PostCardShellContainerColorKey, expected),
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun assertIdentityBandColor(expected: Color) {
        composeTestRule.onNodeWithTag(TOPIC_POST_IDENTITY_BAND_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostIdentityBandContainerColorKey, expected))
    }

    private fun assertIdentityBandContentColor(expected: Color) {
        composeTestRule.onNodeWithTag(TOPIC_POST_IDENTITY_BAND_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostIdentityBandContentColorKey, expected))
    }

    private fun assertTextColor(text: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        val action = requireNotNull(
            composeTestRule.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertTrue(action(layouts))
        org.junit.Assert.assertEquals(expected, layouts.single().layoutInput.style.color)
    }

    private fun assertStateCount(description: String, expected: Int) {
        composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description),
            useUnmergedTree = true,
        ).assertCountEquals(expected)
    }

    private fun samplePost(
        content: PostContent = PostContent(emptyList()),
        signature: PostContent? = null,
        isModerationPost: Boolean = false,
    ): Post = Post(
        numreponse = 874,
        author = EGO_PSEUDO,
        date = Instant.EPOCH,
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        quoteRef = 1,
        profileId = null,
        signature = signature,
        isModerationPost = isModerationPost,
    )

    private fun autoQuoteContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Quote(
                author = EGO_PSEUDO,
                numreponse = 42,
                page = 1,
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Paragraph(inlines = listOf(PostInline.Text("mon ancien message"))),
                    ),
                ),
            ),
        ),
    )

    private fun longAutoQuoteContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Quote(
                author = EGO_PSEUDO,
                numreponse = 42,
                page = 1,
                content = PostContent(
                    blocks = listOf(
                        PostBlock.Paragraph(
                            inlines = listOf(
                                PostInline.Text(
                                    "Mon ancien message est assez long pour rester repliable. ".repeat(12),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val EGO_PSEUDO = "XaTriX"
        const val EGO_CANONICAL = "xatrix"
        const val OWN_POST_STATE = "Votre message"
        const val OWN_QUOTE_STATE = "Citation de votre message"
        const val MODERATION_STATE = "Message de la modération"
        val EGO_POST_LIGHT = Color(0xFFE4EDFF)
        val MODERATION_HEADER_LIGHT = Color(0xFFB71C1C)
        val MODERATION_BODY_LIGHT = Color(0xFFD32F2F)
    }
}
