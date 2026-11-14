package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1112 — screenshot verification and visual review of the real Topic moderation card.
 *
 * The captures deliberately include every colour-sensitive family: body text + link, quote,
 * spoiler, code, signature, citation pill, edit marker, menu and footer actions. A pixel equality assertion
 * pins the persistent moderation red on the band even when the card is the scroll anchor — the
 * moderation header wins above the transient anchor tint. These PNGs are verified references in the
 * default verify mode, and inspection artefacts when re-recorded.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:topic:testDebugUnitTest \
 *         --tests '*TopicModerationRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicModerationRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun moderationDark() = capture(
        darkTheme = true,
        amoledTheme = false,
        highlighted = false,
        fileName = "topic_moderation_dark.png",
    )

    @Test
    fun moderationAmoled() = capture(
        darkTheme = true,
        amoledTheme = true,
        highlighted = false,
        fileName = "topic_moderation_amoled.png",
    )

    @Test
    fun moderationAnchoredLight() = capture(
        darkTheme = false,
        amoledTheme = false,
        highlighted = true,
        fileName = "topic_moderation_anchored_light.png",
    )

    private fun capture(
        darkTheme: Boolean,
        amoledTheme: Boolean,
        highlighted: Boolean,
        fileName: String,
    ) {
        val anchored = mutableStateOf(false)
        composeTestRule.setContent {
            RedfaceTheme(
                darkTheme = darkTheme,
                amoledTheme = amoledTheme,
                dynamicColor = false,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.width(360.dp).padding(12.dp)) {
                        TopicPostCard(
                            post = moderationPost(),
                            highlighted = anchored.value,
                            citedCount = 2,
                            showSignature = true,
                            onQuote = {},
                            onEdit = {},
                            onToggleMultiQuote = {},
                            onGoToCitedPost = { _, _ -> },
                        )
                    }
                }
            }
        }

        if (highlighted) {
            val before = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
            composeTestRule.runOnIdle { anchored.value = true }
            val after = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
            assertTrue("Moderation must override the anchor tint", before.sameAs(after))
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/$fileName",
        )
    }

    private fun moderationPost(): Post = Post(
        numreponse = 1112,
        author = "Modération",
        date = Instant.parse("2026-08-29T10:15:00Z"),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(
                    inlines = listOf(
                        PostInline.Text("Message de modération avec "),
                        PostInline.Link(
                            url = "https://forum.hardware.fr",
                            children = listOf(PostInline.Text("un lien lisible")),
                        ),
                        PostInline.Text("."),
                    ),
                ),
                PostBlock.Quote(
                    author = "MembreTest",
                    numreponse = 42,
                    page = 1,
                    content = paragraph("Citation conservée sur une sous-surface rouge."),
                ),
                PostBlock.Spoiler(
                    label = "Avertissement masqué",
                    content = paragraph("Détail du spoiler."),
                ),
                PostBlock.CodeBlock(
                    text = "if (respecteLaCharte) publier()",
                    language = "kotlin",
                ),
            ),
        ),
        avatarUrl = null,
        isEditable = true,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        signature = paragraph("Signature de la modération"),
        editedAt = Instant.parse("2026-08-29T10:20:00Z"),
        quoteRef = 1,
        citedCount = 2,
        isModerationPost = true,
    )

    private fun paragraph(text: String): PostContent = PostContent(
        blocks = listOf(PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))),
    )
}
