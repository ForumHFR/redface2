package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.PostRenderer
import fr.forumhfr.redface2.core.ui.post.releasePostSelectionOnTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    qualifiers = "w360dp-h780dp-xxhdpi",
    shadows = [NoopTopicShadowMagnifier::class],
)
class TopicSelectionReleaseTest {

    @get:Rule
    val compose = createComposeRule()

    private var doubleTapTimeoutMillis = 0L

    @Test
    fun `long press keeps selection epoch and tap outside selected post advances it`() {
        val epoch = mutableIntStateOf(0)
        setTwoPosts(epoch)

        compose.onNodeWithText(FIRST_POST_TEXT).performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(0, epoch.intValue) }

        compose.onNodeWithTag(SECOND_POST_BACKGROUND_TAG).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, epoch.intValue) }

        compose.onNodeWithTag(SECOND_POST_BACKGROUND_TAG).performTouchInput { click() }
        compose.runOnIdle { assertEquals("the release must disarm after one bump", 1, epoch.intValue) }

        // Compose semantics expose neither Android's selection handles nor its floating toolbar.
        // Their disappearance after the owner reset therefore remains a device-level check; this
        // test pins the observable owner epoch and ResettableSelectionContainerTest pins recreation.
    }

    @Test
    fun `plain tap without a preceding long press keeps selection owners stable`() {
        val epoch = mutableIntStateOf(0)
        setTwoPosts(epoch)

        compose.onNodeWithTag(SECOND_POST_BACKGROUND_TAG).performTouchInput { click() }

        compose.runOnIdle { assertEquals(0, epoch.intValue) }
    }

    @Test
    fun `double tap arms selection and the following outside tap advances its epoch`() {
        val epoch = mutableIntStateOf(0)
        setTwoPosts(epoch)

        compose.onNodeWithText(FIRST_POST_TEXT).performTouchInput { doubleClick() }
        compose.runOnIdle { assertEquals("the selection gesture must not release itself", 0, epoch.intValue) }

        compose.onNodeWithTag(SECOND_POST_BACKGROUND_TAG).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, epoch.intValue) }
    }

    @Test
    fun `two taps beyond the double tap timeout do not arm selection`() {
        val epoch = mutableIntStateOf(0)
        setTwoPosts(epoch)
        compose.runOnIdle { assertTrue(doubleTapTimeoutMillis > 0) }

        compose.onNodeWithText(FIRST_POST_TEXT).performTouchInput {
            click()
            advanceEventTime(doubleTapTimeoutMillis + 1)
            click()
        }
        compose.onNodeWithTag(SECOND_POST_BACKGROUND_TAG).performTouchInput { click() }

        compose.runOnIdle { assertEquals(0, epoch.intValue) }
    }

    @Test
    fun `selection observer does not consume a link tap in another post`() {
        val epoch = mutableIntStateOf(0)
        val uriHandler = RecordingUriHandler()
        setTwoPosts(epoch, uriHandler)

        compose.onNodeWithText(FIRST_POST_TEXT).performTouchInput { longClick() }
        compose.onNodeWithText(LINK_TEXT).performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, epoch.intValue)
            assertEquals(LINK_URL, uriHandler.opened)
        }
    }

    private fun setTwoPosts(epoch: androidx.compose.runtime.MutableIntState, uriHandler: UriHandler? = null) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val viewConfiguration = LocalViewConfiguration.current
                SideEffect { doubleTapTimeoutMillis = viewConfiguration.doubleTapTimeoutMillis }
                val selectionMaybeActive = remember { mutableStateOf(false) }
                val content: @Composable () -> Unit = {
                    Column(
                        modifier = Modifier.releasePostSelectionOnTap(
                            onSelectionPlausible = { selectionMaybeActive.value = true },
                            onTap = {
                                if (selectionMaybeActive.value) {
                                    epoch.intValue += 1
                                    selectionMaybeActive.value = false
                                }
                            },
                        ),
                    ) {
                        PostRenderer(
                            content = paragraph(FIRST_POST_TEXT),
                            selectable = true,
                            selectionEpoch = epoch.intValue,
                        )
                        Column {
                            PostRenderer(
                                content = linkedParagraph(),
                                selectable = true,
                                selectionEpoch = epoch.intValue,
                            )
                            Spacer(
                                Modifier
                                    .testTag(SECOND_POST_BACKGROUND_TAG)
                                    .fillMaxWidth()
                                    .height(48.dp),
                            )
                        }
                    }
                }
                if (uriHandler == null) content() else {
                    CompositionLocalProvider(LocalUriHandler provides uriHandler) { content() }
                }
            }
        }
    }

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(listOf(PostInline.Text(text)))),
    )

    private fun linkedParagraph(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                listOf(PostInline.Link(LINK_URL, listOf(PostInline.Text(LINK_TEXT)))),
            ),
        ),
    )

    private class RecordingUriHandler : UriHandler {
        var opened: String? = null

        override fun openUri(uri: String) {
            opened = uri
        }
    }

    private companion object {
        const val FIRST_POST_TEXT = "Texte sélectionnable du premier post"
        const val LINK_TEXT = "Lien du second post"
        const val LINK_URL = "https://example.org/selection"
        const val SECOND_POST_BACKGROUND_TAG = "second-post-background"
    }
}
