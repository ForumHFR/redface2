package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.RedfaceLightColorScheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #351 — slot contract of [PostCardShell] / [PostIdentityBand]: the mandatory header/body slots
 * always render; the optional badges/footer slots render when supplied and are absent when `null`
 * (the MP case). Border + highlight are colour/stroke affordances (no semantics to assert on
 * Robolectric); they are exercised here only to pin that supplying them does not drop the slots.
 *
 * #884 — flat contract: `flat = true` closes the post with a hairline divider AFTER the slots
 * (suppressed when the multi-quote border already closes it), `flat = false` stays divider-free,
 * and both modes expose an [androidx.compose.ui.semantics.isTraversalGroup] for TalkBack.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostCardShellTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `mandatory header and body always render`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                )
            }
        }

        composeTestRule.onNodeWithText("header").assertIsDisplayed()
        composeTestRule.onNodeWithText("body").assertIsDisplayed()
    }

    @Test
    fun `badges and footer render when supplied`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    badges = { Text("badges") },
                    footer = { Text("footer") },
                )
            }
        }

        composeTestRule.onNodeWithText("badges").assertIsDisplayed()
        composeTestRule.onNodeWithText("footer").assertIsDisplayed()
    }

    @Test
    fun `null badges and footer add no placeholder nodes`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    badges = null,
                    footer = null,
                )
            }
        }

        composeTestRule.onNodeWithText("badges").assertDoesNotExist()
        composeTestRule.onNodeWithText("footer").assertDoesNotExist()
    }

    @Test
    fun `border is honoured without dropping slots`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    border = BorderStroke(2.dp, Color.Red),
                )
            }
        }

        // The bordered card still renders its mandatory slots (the stroke colour itself is not a
        // semantics-asserted property — a Roborazzi golden would cover the pixels).
        composeTestRule.onNodeWithText("header").assertIsDisplayed()
        composeTestRule.onNodeWithText("body").assertIsDisplayed()
    }

    @Test
    fun `container override replaces the inset card colour without dropping slots`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Box(Modifier.fillMaxWidth().height(24.dp)) },
                    body = { Box(Modifier.fillMaxWidth().height(24.dp)) },
                    badges = { Text("override badges") },
                    footer = { Text("override footer") },
                    modifier = Modifier.testTag(OVERRIDE_CARD_TAG),
                    containerColorOverride = OVERRIDE_COLOR,
                )
            }
        }

        composeTestRule.onNodeWithTag(OVERRIDE_CARD_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostCardShellContainerColorKey, OVERRIDE_COLOR))
        composeTestRule.onNodeWithText("override badges").assertIsDisplayed()
        composeTestRule.onNodeWithText("override footer").assertIsDisplayed()
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `container override replaces the flat colour without dropping slots or hairline`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Box(Modifier.fillMaxWidth().height(24.dp)) },
                    body = { Box(Modifier.fillMaxWidth().height(24.dp)) },
                    footer = { Text("flat override footer") },
                    modifier = Modifier.testTag(OVERRIDE_FLAT_TAG),
                    flat = true,
                    containerColorOverride = OVERRIDE_COLOR,
                )
            }
        }

        composeTestRule.onNodeWithTag(OVERRIDE_FLAT_TAG, useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(PostCardShellContainerColorKey, OVERRIDE_COLOR))
        composeTestRule.onNodeWithText("flat override footer").assertIsDisplayed()
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `flat mode closes the post with a divider after the slots`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    flat = true,
                    badges = { Text("badges") },
                    footer = { Text("footer") },
                )
            }
        }

        // #884 — the flat post has no card boundary left: the closing hairline is what separates it
        // from the next post. The slots themselves are untouched by the mode switch.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertExists()
        composeTestRule.onNodeWithText("header").assertIsDisplayed()
        composeTestRule.onNodeWithText("badges").assertIsDisplayed()
        composeTestRule.onNodeWithText("body").assertIsDisplayed()
        composeTestRule.onNodeWithText("footer").assertIsDisplayed()
    }

    @Test
    fun `flat mode with a multi-quote border renders no divider`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    flat = true,
                    border = BorderStroke(2.dp, Color.Red),
                )
            }
        }

        // #884 — the multi-quote outline (#436) already closes the post on all four sides; adding
        // the hairline under it would double-stroke the bottom edge.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("header").assertIsDisplayed()
        composeTestRule.onNodeWithText("body").assertIsDisplayed()
    }

    @Test
    fun `flat mode renders no divider when the sequence owner takes the bottom edge`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    flat = true,
                    footer = { Text("footer") },
                    flatBottomEdge = PostCardShellFlatBottomEdge.NONE,
                )
            }
        }

        // #983 — the next rendered element brings its own boundary (a separator rule, an island's
        // card border): the shell must not stack a second trait a few dp above it. Only the OWNER
        // of the sequence can know this, so the shell renders the decision, it does not take it.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
        // The slots are untouched by the edge decision.
        composeTestRule.onNodeWithText("header").assertIsDisplayed()
        composeTestRule.onNodeWithText("body").assertIsDisplayed()
        composeTestRule.onNodeWithText("footer").assertIsDisplayed()
    }

    @Test
    fun `card mode renders no divider`() {
        composeTestRule.setContent {
            RedfaceTheme {
                PostCardShell(
                    header = { Text("header") },
                    body = { Text("body") },
                    badges = { Text("badges") },
                    footer = { Text("footer") },
                )
            }
        }

        // The default (card) rendering is strictly the pre-#884 one — no hairline sneaks in.
        composeTestRule.onNodeWithTag(POST_CARD_SHELL_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `both modes expose a traversal group`() {
        composeTestRule.setContent {
            RedfaceTheme {
                Column {
                    PostCardShell(
                        header = { Text("card header") },
                        body = { Text("card body") },
                    )
                    PostCardShell(
                        header = { Text("flat header") },
                        body = { Text("flat body") },
                        flat = true,
                    )
                }
            }
        }

        // #884 a11y — each post is one TalkBack traversal group whatever the mode. M3's Surface only
        // sets the deprecated IsContainer key, so these two IsTraversalGroup nodes are the shell's own.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true),
                useUnmergedTree = true,
            )
            .assertCountEquals(2)
    }

    @Test
    fun `identity band hosts its content under any caller-supplied tint`() {
        composeTestRule.setContent {
            RedfaceTheme {
                // The tint is the call-site's decision (containerColor), not the band's: it hosts the
                // content slot whatever colour it is given — the structural contract a non-pixel test pins.
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    content = { Text("anchor band") },
                )
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    content = { Text("normal band") },
                )
            }
        }

        composeTestRule.onNodeWithText("anchor band").assertIsDisplayed()
        composeTestRule.onNodeWithText("normal band").assertIsDisplayed()
        composeTestRule.onNode(
            SemanticsMatcher.expectValue(
                PostIdentityBandContainerColorKey,
                RedfaceLightColorScheme.tertiaryContainer,
            ),
            useUnmergedTree = true,
        ).assert(
            SemanticsMatcher.expectValue(
                PostIdentityBandContentColorKey,
                RedfaceLightColorScheme.onTertiaryContainer,
            ),
        )
    }

    private companion object {
        const val OVERRIDE_CARD_TAG = "override-card"
        const val OVERRIDE_FLAT_TAG = "override-flat"
        val OVERRIDE_COLOR = Color(0xFF123456)
    }
}
