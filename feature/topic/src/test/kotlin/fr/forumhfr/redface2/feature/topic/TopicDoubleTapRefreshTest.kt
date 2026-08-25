package fr.forumhfr.redface2.feature.topic

import android.widget.Magnifier
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostListScaffold
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [NoopTopicShadowMagnifier::class],
)
class TopicDoubleTapRefreshTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `double tap on selectable post text is consumed and never refreshes`() {
        var refreshCount = 0
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                PostListScaffold(
                    listState = LazyListState(),
                    contentPadding = topicListContentPadding(fullWidthPosts = false),
                    verticalArrangement = topicListArrangement(fullWidthPosts = false),
                    listModifier = Modifier.topicDoubleTapRefresh { refreshCount += 1 },
                ) {
                    item { }
                    item(key = POST_NUMREPONSE) {
                        TopicPostCard(
                            post = textPost(),
                            citedCount = 0,
                            onQuote = null,
                            onEdit = null,
                        )
                    }
                }
            }
        }

        compose.onNodeWithText(BODY_TEXT).performTouchInput { doubleClick() }

        // ReadingPostCard's SelectionContainer consumes word selection before the list detector.
        assertEquals(0, refreshCount)
    }

    private fun textPost(): Post = Post(
        numreponse = POST_NUMREPONSE,
        author = "Alice",
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
        const val POST_NUMREPONSE = 42
        const val BODY_TEXT = "Corps sélectionnable du sujet"
    }
}

/** Robolectric has no real Surface for the platform text-selection magnifier popup. */
@Implements(Magnifier::class)
class NoopTopicShadowMagnifier {

    @Implementation
    @Suppress("UnusedParameter")
    fun show(sourceCenterX: Float, sourceCenterY: Float) = Unit

    @Implementation
    @Suppress("UnusedParameter")
    fun show(sourceCenterX: Float, sourceCenterY: Float, magnifierCenterX: Float, magnifierCenterY: Float) = Unit

    @Implementation
    fun update() = Unit

    @Implementation
    fun dismiss() = Unit
}
