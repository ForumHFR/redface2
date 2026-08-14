package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.DisplayMetrics
import fr.forumhfr.redface2.core.ui.theme.LocalDisplayMetrics
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1042 — invariants owned by the shared reading card rather than either feature adapter: its
 * neutral host needs only identity + body, footer presence alone controls the bottom gutter,
 * signatures stay gated/subdued/colour-neutral, and a missing image callback leaves images inert.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ReadingPostCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(IMAGE_URL, ColorImage(0xFF1565C0.toInt(), width = 400, height = 300))
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `neutral card renders identity and body without optional capabilities or slots`() {
        composeTestRule.setContent {
            TestTheme {
                ReadingPostCard(
                    post = samplePost(
                        content = paragraph(BODY_TEXT),
                        signature = paragraph(SIGNATURE_TEXT),
                    ),
                    identity = { Text(IDENTITY_TEXT) },
                )
            }
        }

        composeTestRule.onNodeWithText(IDENTITY_TEXT).assertIsDisplayed()
        composeTestRule.onNodeWithText(BODY_TEXT).assertIsDisplayed()
        // The neutral presentation hides signatures and no absent slot emits a placeholder.
        composeTestRule.onNodeWithText(SIGNATURE_TEXT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(READING_POST_SIGNATURE_DIVIDER_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `body owns the exact bottom gutter only while the footer slot is absent`() {
        val footerPresent = mutableStateOf(false)
        composeTestRule.setContent {
            TestTheme {
                CompositionLocalProvider(LocalDisplayMetrics provides TEST_METRICS) {
                    val footer: (@Composable () -> Unit)? = if (footerPresent.value) {
                        {}
                    } else {
                        null
                    }
                    ReadingPostCard(
                        post = samplePost(content = paragraph(BODY_TEXT)),
                        identity = { Text(IDENTITY_TEXT) },
                        modifier = Modifier.testTag(CARD_TAG),
                        footer = footer,
                    )
                }
            }
        }

        val heightWithoutFooter = composeTestRule
            .onNodeWithTag(CARD_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height

        composeTestRule.runOnIdle { footerPresent.value = true }

        val heightWithFooter = composeTestRule
            .onNodeWithTag(CARD_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height
        with(composeTestRule.density) {
            assertEquals(
                "an empty footer must remove exactly cardBodyBottom from the body",
                TEST_METRICS.cardBodyBottom.value,
                (heightWithoutFooter - heightWithFooter).toDp().value,
                DP_TOLERANCE,
            )
        }
    }

    @Test
    fun `signature renders below a divider and without author colours`() {
        composeTestRule.setContent {
            TestTheme {
                ReadingPostCard(
                    post = samplePost(
                        content = colouredParagraph(BODY_TEXT),
                        signature = colouredParagraph(SIGNATURE_TEXT),
                    ),
                    identity = { Text(IDENTITY_TEXT) },
                    presentation = ReadingPostCardPresentation(showSignature = true),
                )
            }
        }

        composeTestRule.onNodeWithTag(READING_POST_SIGNATURE_DIVIDER_TAG, useUnmergedTree = true)
            .assertExists()
        val body = composeTestRule.onNodeWithText(BODY_TEXT, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val signature = composeTestRule.onNodeWithText(SIGNATURE_TEXT, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single()

        assertTrue(
            "the body must keep its author colour",
            body.spanStyles.any { it.item.color != Color.Unspecified },
        )
        assertFalse(
            "the signature provider must drop author colours",
            signature.spanStyles.any { it.item.color != Color.Unspecified },
        )
        // A pin of the CONSTANT, not proof of rendering: `Modifier.alpha` leaves no semantics to
        // assert on and Roborazzi is record-only here (ADR-016), so an accidental edit of the
        // historical 0.7f is what this line catches — nothing more.
        assertEquals("historical signature alpha", 0.7f, READING_POST_SIGNATURE_ALPHA, 0f)
    }

    @Test
    fun `image stays inert when no long press capability is supplied`() {
        composeTestRule.setContent {
            TestTheme {
                ReadingPostCard(
                    post = samplePost(
                        content = PostContent(
                            blocks = listOf(PostBlock.Image(url = IMAGE_URL, description = IMAGE_DESCRIPTION)),
                        ),
                    ),
                    identity = { Text(IDENTITY_TEXT) },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(IMAGE_DESCRIPTION)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Composable
    private fun TestTheme(content: @Composable () -> Unit) {
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false, content = content)
    }

    private fun samplePost(
        content: PostContent,
        signature: PostContent? = null,
    ): Post = Post(
        numreponse = 1042,
        author = "Lecteur",
        date = Instant.EPOCH,
        content = content,
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        signature = signature,
    )

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))),
    )

    private fun colouredParagraph(text: String): PostContent = PostContent(
        blocks = listOf(
            PostBlock.Paragraph(
                inlines = listOf(
                    PostInline.Color(
                        colorHex = "#CC0000",
                        children = listOf(PostInline.Text(text)),
                    ),
                ),
            ),
        ),
    )

    private companion object {
        const val BODY_TEXT = "shared reading body"
        const val SIGNATURE_TEXT = "shared reading signature"
        const val IDENTITY_TEXT = "shared reading identity"
        const val IMAGE_DESCRIPTION = "shared reading image"
        const val IMAGE_URL = "https://rehost.diberie.com/Picture/Get/f/reading-card.png"
        const val CARD_TAG = "ReadingPostCardUnderTest"
        const val DP_TOLERANCE = 0.01f

        val TEST_METRICS = DisplayMetrics(
            cardBodyHorizontal = 12.dp,
            cardBodyTop = 10.dp,
            cardBodyBottom = 23.dp,
            cardHeaderVertical = 6.dp,
            listRowVertical = 10.dp,
            postSpacing = 8.dp,
        )
    }
}
