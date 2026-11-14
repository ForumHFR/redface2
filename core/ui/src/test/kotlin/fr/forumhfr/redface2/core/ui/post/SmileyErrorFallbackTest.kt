package fr.forumhfr.redface2.core.ui.post

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.parser.BbcodeContentParser
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.BbcodePreview
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #416 — a smiley sprite that fails to load (deleted perso, stale URL) must fall back to its
 * typed token, never render as blank. Web and RF1 both show the code in that case ; before the
 * fix, `smileyInlineContent` used a slot-less `AsyncImage` and the inline box stayed empty.
 *
 * The fake engine only intercepts [aliveUrl] — any other URL (the dead smiley) resolves to a
 * Coil error result, which is exactly the production failure mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SmileyErrorFallbackTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val aliveUrl = "https://forum-images.hardware.fr/images/perso/1/alive.gif"
    private val deadUrl = "https://forum-images.hardware.fr/images/perso/1/deleted-long-ago.gif"

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = FakeImageLoaderEngine.Builder()
            .intercept(aliveUrl, ColorImage(0xFF2E7D32.toInt(), width = 40, height = 40))
            // deadUrl intentionally NOT intercepted → Coil error result.
            .build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @After
    fun resetImageLoader() { SingletonImageLoader.reset() }

    @Test
    fun `ED66 derived perso in preview falls back to the full typed token on image failure`() {
        val token = "[:deleted-long-ago:1]"
        val content = BbcodeContentParser().parse(token)
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalMediaAttemptLedger provides ledger,
                    LocalIntrinsicMediaSizeCache provides cache) {
                    BbcodePreview(content, mediaDiskCachePolicy = PostMediaDiskCachePolicy.DISABLED)
                }
            }
        }
        // The paragraph exposes the token as alternative text even before the image fails. Assert
        // the displayed inline Text child, not that parent (or an arbitrary first text match).
        val fallback = hasText(token) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(PostRendererBodyColorKey))
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val now = System.currentTimeMillis()
            val failed = ledger.isFailedFresh(deadUrl, MediaAttemptKind.PROBE, now) ||
                ledger.isFailedFresh(deadUrl, MediaAttemptKind.PAINTER, now)
            failed && composeTestRule.onAllNodes(fallback, useUnmergedTree = true).fetchSemanticsNodes().size == 1
        }
        composeTestRule.onNode(fallback, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a dead smiley falls back to its typed token instead of blank`() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    PostRenderer(
                        content = PostContent(
                            blocks = listOf(
                                PostBlock.Paragraph(
                                    inlines = listOf(
                                        PostInline.Text("avant "),
                                        PostInline.Smiley(
                                            kind = SmileyKind.Perso("deleted-long-ago"),
                                            imageUrl = deadUrl,
                                        ),
                                        PostInline.Text(" après"),
                                    ),
                                ),
                            ),
                        ),
                    )
                }
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodes(
                androidx.compose.ui.test.hasText("[:deleted-long-ago]"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("[:deleted-long-ago]").assertExists()
    }
}
