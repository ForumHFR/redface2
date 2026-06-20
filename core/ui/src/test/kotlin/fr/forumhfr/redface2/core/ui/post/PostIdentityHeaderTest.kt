package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
