package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
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
    fun `null badges and footer are absent (MP case)`() {
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
    fun `identity band hosts its content under any caller-supplied tint`() {
        composeTestRule.setContent {
            RedfaceTheme {
                // The tint is the call-site's decision (containerColor), not the band's: it hosts the
                // content slot whatever colour it is given — the structural contract a non-pixel test pins.
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    content = { Text("anchor band") },
                )
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    content = { Text("normal band") },
                )
            }
        }

        composeTestRule.onNodeWithText("anchor band").assertIsDisplayed()
        composeTestRule.onNodeWithText("normal band").assertIsDisplayed()
    }
}
