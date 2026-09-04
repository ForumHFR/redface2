package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.domain.preferences.PostImageCorners
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #985 — app-root plumbing for content-image corners, with the historical 8 dp default. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostImageCornersThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the local defaults to ROUNDED for unthemed hosts`() {
        var seen: PostImageCorners? = null
        composeTestRule.setContent {
            seen = LocalPostImageCorners.current
        }
        assertEquals(PostImageCorners.DEFAULT, seen)
    }

    @Test
    fun `the reading settings default to ROUNDED`() {
        assertEquals(PostImageCorners.DEFAULT, ReadingDisplaySettings().postImageCorners)
    }

    @Test
    fun `RedfaceTheme provides the post image corners from the reading settings`() {
        var seen: PostImageCorners? = null
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                amoledTheme = false,
                dynamicColor = false,
                reading = ReadingDisplaySettings(postImageCorners = PostImageCorners.SQUARE),
            ) {
                seen = LocalPostImageCorners.current
            }
        }
        assertEquals(PostImageCorners.SQUARE, seen)
    }
}
