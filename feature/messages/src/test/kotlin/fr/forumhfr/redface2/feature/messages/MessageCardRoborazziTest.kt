package fr.forumhfr.redface2.feature.messages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.post.ReadingPostCardPresentation
import fr.forumhfr.redface2.testing.captureRoboImage
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual control for the real MP [MessageCard] (#1040) — one narrow S10e-width capture stacking
 * the pixel-distinct states reachable in a private conversation:
 *
 * - a neutral received message;
 * - an edited message with HFR's citation-count pill;
 * - the connected user's message with the EgoPost container highlight.
 *
 * Every author and body below is synthetic: a private-message capture must never embed a real
 * correspondent or conversation excerpt (#316). A creator-gold case is deliberately absent because
 * it would require the real creator pseudo; a blacklisted message renders [HiddenPostCard], not
 * [MessageCard].
 *
 * References are compared by default without the upstream Gradle plugin (#1571).
 * Add `-Proborazzi.mode=record` only to approve an intentional visual update:
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:messages:testDebugUnitTest \
 *         --tests '*MessageCardRoborazziTest*' --console=plain --no-daemon
 *
 * Output: `feature/messages/src/test/screenshots/message_card_identity_states.png`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class MessageCardRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun messageCardIdentityStates() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Message reçu", style = MaterialTheme.typography.labelMedium)
                        MessageCard(message = sampleMessage(1, "NuageBleu", RECEIVED_BODY))

                        Text("Édité et cité", style = MaterialTheme.typography.labelMedium)
                        MessageCard(
                            message = sampleMessage(2, "PlumeVerte", METADATA_BODY).copy(
                                editedAt = Instant.parse("2026-08-02T14:30:00Z"),
                                citedCount = 3,
                            ),
                        )

                        Text("Votre message", style = MaterialTheme.typography.labelMedium)
                        MessageCard(
                            message = sampleMessage(3, "MonPseudoTest", OWN_BODY, isOwn = true),
                            presentation = ReadingPostCardPresentation(egoPostHighlighted = true),
                        )
                    }
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/message_card_identity_states.png",
        )
    }

    private fun sampleMessage(
        numreponse: Int,
        author: String,
        body: String,
        isOwn: Boolean = false,
    ): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.parse("2026-08-01T10:15:00Z").plusSeconds(numreponse.toLong()),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(body))),
            ),
        ),
        avatarUrl = null,
        isEditable = isOwn,
        isOwnPost = isOwn,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private companion object {
        const val RECEIVED_BODY = "Message de démonstration sans donnée privée."
        const val METADATA_BODY = "Ce cas rend visibles le marqueur d'édition et la pill de citation."
        const val OWN_BODY = "Le fond distingue visuellement le message du compte connecté."
    }
}
