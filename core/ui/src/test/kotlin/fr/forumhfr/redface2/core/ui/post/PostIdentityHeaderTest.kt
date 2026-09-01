package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #351 — contract of [PostIdentityHeader]: the author/date always render; the default pseudo is a
 * plain [Text] of the author and a supplied `pseudo` slot replaces it; the optional `trailing` and
 * `subline` slots appear only when supplied; the avatar and author-pseudo clicks fire their
 * callbacks.
 *
 * #884 — a11y contract: the fallback pseudo [Text] carries heading semantics itself (the plain MP
 * case); a caller-supplied `pseudo` slot OWNS its heading (the topic and creator-MP branches mark
 * their real pseudo text node) and the header adds none around it — one heading per post, TalkBack
 * never announces it twice. Both directions of the slot contract are pinned here (review Sol r4):
 * a slot WITH its own heading() yields exactly one, a slot WITHOUT yields zero — by design.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostIdentityHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `avatar author and date render with default pseudo`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "MonPseudo",
                    avatarUrl = null,
                    dateText = "12/06/2026 10:00:00",
                )
            }
        }

        // The default pseudo is the author text; the date is its own line. The avatar (null URL)
        // falls back to the initial-letter placeholder — its TalkBack announcement carries the author.
        composeTestRule.onNodeWithText("MonPseudo").assertIsDisplayed()
        composeTestRule.onNodeWithText("12/06/2026 10:00:00").assertIsDisplayed()
    }

    @Test
    fun `supporting colour override reaches the date at full opacity`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "Modération",
                    avatarUrl = null,
                    dateText = "date modération",
                    supportingContentColorOverride = Color.White,
                )
            }
        }

        val layouts = mutableListOf<TextLayoutResult>()
        val readLayout = requireNotNull(
            composeTestRule.onNodeWithText("date modération")
                .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action,
        )
        assertEquals(true, readLayout(layouts))
        assertEquals(Color.White, layouts.single().layoutInput.style.color)
        assertEquals(1f, layouts.single().layoutInput.style.color.alpha, 0f)
    }

    @Test
    fun `supplied pseudo slot replaces the default author text`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "RawAuthor",
                    avatarUrl = null,
                    dateText = "date",
                    pseudo = { Text("CustomPseudo") },
                )
            }
        }

        composeTestRule.onNodeWithText("CustomPseudo").assertIsDisplayed()
        // The default author Text is NOT rendered when the pseudo slot is supplied.
        composeTestRule.onNodeWithText("RawAuthor").assertDoesNotExist()
    }

    @Test
    fun `trailing and subline render when supplied and are absent otherwise`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "author",
                    avatarUrl = null,
                    dateText = "date",
                    trailing = { Text("trailing") },
                    subline = { Text("subline") },
                )
            }
        }

        composeTestRule.onNodeWithText("trailing").assertIsDisplayed()
        composeTestRule.onNodeWithText("subline").assertIsDisplayed()
    }

    @Test
    fun `no trailing nor subline (MP case)`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "author",
                    avatarUrl = null,
                    dateText = "date",
                )
            }
        }

        composeTestRule.onNodeWithText("trailing").assertDoesNotExist()
        composeTestRule.onNodeWithText("subline").assertDoesNotExist()
    }

    @Test
    fun `default pseudo carries heading semantics - and is the only heading`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "MonPseudo",
                    avatarUrl = null,
                    dateText = "date",
                )
            }
        }

        // #884 a11y — TalkBack's heading navigation jumps from post to post on the identity line.
        // The fallback pseudo Text carries heading() itself (no synthetic contentDescription — the
        // author text IS the announcement, so nothing is spoken twice)…
        composeTestRule.onNodeWithText("MonPseudo")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        // …and it is the ONLY heading the header emits (review Sol r4) — this is the plain-pseudo
        // MP production path, one heading per message.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
    }

    @Test
    fun `supplied pseudo slot owns its heading - the header adds none`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "RawAuthor",
                    avatarUrl = null,
                    dateText = "date",
                    pseudo = {
                        Text(
                            text = "CustomPseudo",
                            modifier = Modifier.semantics { heading() },
                        )
                    },
                )
            }
        }

        // #884 a11y (vague 3) — a caller-supplied pseudo marks its OWN text node (the topic puts
        // heading() on the real pseudo Text — best TalkBack target); the header must NOT wrap the
        // slot in a second heading node, or every post would announce its heading twice.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
        composeTestRule
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
                    .and(hasText("CustomPseudo")),
                useUnmergedTree = true,
            )
            .assertExists()
    }

    @Test
    fun `provided slot without heading yields none - callers own the heading (contract)`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "RawAuthor",
                    avatarUrl = null,
                    dateText = "date",
                    pseudo = { Text("CustomPseudo") },
                )
            }
        }

        // #884 contract (review Sol r4) — NOT a bug of the component: the header never wraps a
        // supplied slot in a heading (it would double the announcement for every caller that marks
        // its own node, cf. the test above), so a slot that sets no heading() yields ZERO heading.
        // Owning the heading is part of the slot's contract — cf. the [pseudo] KDoc of
        // PostIdentityHeader; each production variant carries its own exactly-one-heading guard
        // (TopicPostCardFullWidthTest, MessageCardShellSmokeTest).
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                useUnmergedTree = true,
            )
            .assertCountEquals(0)
    }

    @Test
    fun `onAuthorClick fires when the default pseudo is tapped`() {
        var clicks = 0
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "Tappable",
                    avatarUrl = null,
                    dateText = "date",
                    onAuthorClick = { clicks++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Tappable").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `onAvatarClick fires when the avatar is tapped`() {
        var clicks = 0
        composeTestRule.setContent {
            RedfaceTheme {
                PostIdentityHeader(
                    author = "Author",
                    avatarUrl = null,
                    dateText = "date",
                    onAvatarClick = { clicks++ },
                    onAvatarClickLabel = "open profile",
                )
            }
        }

        // The avatar (null URL) renders the initial-letter placeholder, which carries the author
        // content description ("Avatar de <author>"); the clickable wrapper around it carries the
        // tap. Targeting the avatar by its content description and clicking fires onAvatarClick.
        composeTestRule.onNodeWithContentDescription("Avatar de Author").performClick()
        assertEquals(1, clicks)
    }
}
