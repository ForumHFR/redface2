package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1112 — record-only visual review of the real Topic moderation card.
 *
 * The captures deliberately include every colour-sensitive family: body text + link, quote,
 * spoiler, code, signature, citation pill, edit marker, menu and footer actions. The fourth image
 * pins the transient anchor band above the persistent moderation body. These PNGs are inspection
 * artefacts, not self-validating snapshots.
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
    fun moderationLight() = capture(
        darkTheme = false,
        amoledTheme = false,
        highlighted = false,
        fileName = "topic_moderation_light.png",
    )

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
                            highlighted = highlighted,
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

        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/$fileName",
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
