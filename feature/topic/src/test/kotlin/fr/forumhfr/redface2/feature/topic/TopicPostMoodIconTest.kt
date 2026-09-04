package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.HFR_MESSAGE_ICON_BASE_URL
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@OptIn(ExperimentalTestApi::class)
class TopicPostMoodIconTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept("${HFR_MESSAGE_ICON_BASE_URL}6.gif", ColorImage(0xFF1565C0.toInt()))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `topic header shows a non-default message tone`() {
        render(msgIcon = 6)

        composeTestRule.onNodeWithContentDescription(MOOD_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `topic header omits the default message tone`() {
        render(msgIcon = null)

        composeTestRule.onNodeWithContentDescription(MOOD_DESCRIPTION).assertDoesNotExist()
    }

    private fun render(msgIcon: Int?) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPostCard(
                    post = samplePost(msgIcon),
                    citedCount = 0,
                    onQuote = null,
                    onEdit = null,
                )
            }
        }
    }

    private fun samplePost(msgIcon: Int?): Post = Post(
        numreponse = 2800343,
        author = "Auteur",
        date = Instant.EPOCH,
        content = PostContent(emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        msgIcon = msgIcon,
    )

    private companion object {
        const val MOOD_DESCRIPTION = "Ton du message"
    }
}
