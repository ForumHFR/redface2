package fr.forumhfr.redface2.core.ui.theme

import androidx.compose.ui.test.junit4.v2.createComposeRule
import fr.forumhfr.redface2.core.domain.preferences.PostImageMaxWidth
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #991 — app-root plumbing for the post content-image fImage cap. The default stays P95 so
 * unprovided previews and hosts keep the historical 0.95 width cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PostImageMaxWidthThemeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the local defaults to P95 for unthemed hosts`() {
        var seen: PostImageMaxWidth? = null
        composeTestRule.setContent {
            seen = LocalPostImageMaxWidth.current
        }
        assertEquals(PostImageMaxWidth.DEFAULT, seen)
    }

    @Test
    fun `the reading settings default to P95`() {
        assertEquals(PostImageMaxWidth.DEFAULT, ReadingDisplaySettings().postImageMaxWidth)
    }

    @Test
    fun `RedfaceTheme provides the post image max width from the reading settings`() {
        var seen: PostImageMaxWidth? = null
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = false,
                amoledTheme = false,
                dynamicColor = false,
                reading = ReadingDisplaySettings(postImageMaxWidth = PostImageMaxWidth.P100),
            ) {
                seen = LocalPostImageMaxWidth.current
            }
        }
        assertEquals(PostImageMaxWidth.P100, seen)
    }
}
