package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostCardShellContainerColorKey
import fr.forumhfr.redface2.core.ui.post.PostIdentityBandContainerColorKey
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import fr.forumhfr.redface2.core.ui.theme.egoHighlightColors
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1040 — MP identity-band contract on the real [MessageCard]:
 *  - the fixed `secondaryContainer` band spans the card in inset and full-width modes;
 *  - it remains inside the shell, whose Card clips it with the active rounded/rectangular shape;
 *  - the band adds no spacing: the historical MP 12.dp horizontal / 10.dp vertical gutters stay on
 *    the header and body slots in both modes;
 *  - EgoPost colours only the card container and leaves the band fixed, while its state description
 *    and the pseudo's exactly-one heading keep their existing accessibility contract.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageCardIdentityBandTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `inset card band spans its shell and preserves MP gutters`() {
        setCard(horizontalInset = 16.dp)

        shellNode(RedfaceLightColorScheme.surfaceContainer)
            .assertLeftPositionInRootIsEqualTo(16.dp)
            .assertWidthIsEqualTo(328.dp)
        bandNode()
            .assertLeftPositionInRootIsEqualTo(16.dp)
            .assertWidthIsEqualTo(328.dp)
            .assert(hasAnyAncestor(shellMatcher(RedfaceLightColorScheme.surfaceContainer)))
        assertHistoricalMpGutters(cardLeft = 16.dp)
    }

    @Test
    fun `full width card band stays inside the rectangular edge to edge shell`() {
        setCard(flat = true)

        shellNode(Color.Transparent)
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
        bandNode()
            .assertLeftPositionInRootIsEqualTo(0.dp)
            .assertWidthIsEqualTo(360.dp)
            .assert(hasAnyAncestor(shellMatcher(Color.Transparent)))
        assertHistoricalMpGutters(cardLeft = 0.dp)
    }

    @Test
    fun `EgoPost colours only the card while the identity band stays fixed across modes`() {
        val flat = mutableStateOf(false)
        var expectedEgoPostColor = Color.Unspecified
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                expectedEgoPostColor = egoHighlightColors().postContainer
                MessageCard(
                    message = sampleMessage(),
                    presentation = ReadingPostCardPresentation(
                        flat = flat.value,
                        egoPostHighlighted = true,
                    ),
                )
            }
        }

        assertEgoPostBandContract(expectedShellColor = expectedEgoPostColor)

        compose.runOnIdle { flat.value = true }

        assertEgoPostBandContract(expectedShellColor = expectedEgoPostColor)
    }

    private fun setCard(flat: Boolean = false, horizontalInset: Dp = 0.dp) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalInset),
                ) {
                    MessageCard(
                        message = sampleMessage(),
                        presentation = ReadingPostCardPresentation(flat = flat),
                    )
                }
            }
        }
    }

    private fun assertHistoricalMpGutters(cardLeft: Dp) {
        compose.onNodeWithContentDescription("Avatar de XaTriX")
            .assertLeftPositionInRootIsEqualTo(cardLeft + 12.dp)
        compose.onNodeWithText(BODY_TEXT, useUnmergedTree = true)
            .assertLeftPositionInRootIsEqualTo(cardLeft + 12.dp)

        val bandBounds = bandNode().fetchSemanticsNode().boundsInRoot
        val avatarBounds = compose.onNodeWithContentDescription("Avatar de XaTriX")
            .fetchSemanticsNode().boundsInRoot
        val bodyBounds = compose.onNodeWithText(BODY_TEXT, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(
            "the band must add no padding above the historical MP header inset",
            10f,
            with(compose.density) { (avatarBounds.top - bandBounds.top).toDp().value },
            DP_TOLERANCE,
        )
        assertEquals(
            "the body must keep its historical gap below the identity band",
            10f,
            with(compose.density) { (bodyBounds.top - bandBounds.bottom).toDp().value },
            DP_TOLERANCE,
        )
    }

    private fun assertEgoPostBandContract(expectedShellColor: Color) {
        shellNode(expectedShellColor).assertExists()
        bandNode().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, OWN_POST_STATE),
        )
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNode(heading.and(hasText("XaTriX")), useUnmergedTree = true).assertExists()
        compose.onAllNodes(heading, useUnmergedTree = true).assertCountEquals(1)
    }

    private fun shellNode(expectedColor: Color) = compose.onNode(
        shellMatcher(expectedColor),
        useUnmergedTree = true,
    )

    private fun shellMatcher(expectedColor: Color) =
        SemanticsMatcher.expectValue(PostCardShellContainerColorKey, expectedColor)

    private fun bandNode() = compose.onNode(
        SemanticsMatcher.expectValue(
            PostIdentityBandContainerColorKey,
            RedfaceLightColorScheme.secondaryContainer,
        ),
        useUnmergedTree = true,
    )

    private fun sampleMessage(): Post = Post(
        numreponse = 1,
        author = "XaTriX",
        date = Instant.EPOCH,
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(BODY_TEXT))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private companion object {
        const val BODY_TEXT = "bonjour"
        const val OWN_POST_STATE = "Votre message"
        const val DP_TOLERANCE = 0.01f
    }
}
