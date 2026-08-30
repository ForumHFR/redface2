package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #783 — record-only visual review of the reverse-citation sheet in its three user-visible data
 * states and the three static colour schemes. The nine PNGs are inspection artefacts, not
 * versioned baselines.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:topic:testDebugUnitTest \
 *         --tests '*CitingPostsSheetRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class CitingPostsSheetRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun citingPostsLight() = captureTheme(darkTheme = false, amoledTheme = false, suffix = "light")

    @Test
    fun citingPostsDark() = captureTheme(darkTheme = true, amoledTheme = false, suffix = "dark")

    @Test
    fun citingPostsAmoled() = captureTheme(darkTheme = true, amoledTheme = true, suffix = "amoled")

    @Test
    fun citedCardLight() = captureCitedCard(darkTheme = false, amoledTheme = false, suffix = "light")

    @Test
    fun citedCardDark() = captureCitedCard(darkTheme = true, amoledTheme = false, suffix = "dark")

    @Test
    fun citedCardAmoled() = captureCitedCard(darkTheme = true, amoledTheme = true, suffix = "amoled")

    @Test
    fun positiveCitationBadgeIsClickable() {
        val post = citingPost(42, "Auteur", "Texte du post", "2026-08-30T12:00:00Z")
        var clicked: Post? = null
        compose.setContent {
            RedfaceTheme(dynamicColor = false) {
                TopicPostCard(
                    post = post,
                    citedCount = 3,
                    onQuote = null,
                    onEdit = null,
                    onCitedBadgeClick = { clicked = it },
                )
            }
        }

        compose.onNodeWithText("cité 3 fois").performClick()

        assertEquals(post, clicked)
    }

    // #783 (gate Fable R1) — proof that the clickable « cité N fois » pill does NOT inflate the post
    // card: minimumInteractiveComponentSize is neutralized around the pill, so the card keeps its #882
    // density. Record-only, one card per colour scheme for visual review.
    private fun captureCitedCard(darkTheme: Boolean, amoledTheme: Boolean, suffix: String) {
        compose.setContent {
            RedfaceTheme(darkTheme = darkTheme, amoledTheme = amoledTheme, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    TopicPostCard(
                        post = citingPost(
                            42,
                            "Auteur",
                            "Un post cité plusieurs fois dans le sujet.",
                            "2026-08-30T12:00:00Z",
                        ),
                        citedCount = 3,
                        onQuote = null,
                        onEdit = null,
                        onCitedBadgeClick = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/topic_cited_card_$suffix.png",
        )
    }

    private fun captureTheme(darkTheme: Boolean, amoledTheme: Boolean, suffix: String) {
        val scenario = mutableStateOf(CitingScenario.Loaded)
        compose.setContent {
            RedfaceTheme(
                darkTheme = darkTheme,
                amoledTheme = amoledTheme,
                dynamicColor = false,
            ) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    CitingPostsSheet(
                        state = scenario.value.state(),
                        onDismiss = {},
                        onPostClick = {},
                    )
                }
            }
        }

        CitingScenario.entries.forEach { next ->
            compose.runOnIdle { scenario.value = next }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                filePath = "build/outputs/roborazzi/topic_citing_posts_${next.fileName}_$suffix.png",
            )
        }
    }

    private fun CitingScenario.state(): CitingPostsSheetState = CitingPostsSheetState(
        numreponse = 23_786_379,
        citedCount = 5,
        content = when (this) {
            CitingScenario.Loaded -> CitingPostsSheetContent.Loaded(
                listOf(
                    citingPost(23_786_634, "Profil supprimé", "Le contenu a été effacé.", "2010-08-30T15:56:33Z"),
                    citingPost(67_998_657, "Dolores", "Cette règle mérite un rappel.", "2023-03-11T01:53:57Z"),
                    citingPost(74_328_265, "qwazer", "La culture et la confiture…", "2026-03-01T19:03:41Z"),
                ),
            )
            CitingScenario.Loading -> CitingPostsSheetContent.Loading
            CitingScenario.Empty -> CitingPostsSheetContent.Empty
        },
    )

    private fun citingPost(
        numreponse: Int,
        author: String,
        text: String,
        date: String,
    ): Post = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.parse(date),
        content = PostContent(
            blocks = listOf(
                PostBlock.Paragraph(inlines = listOf(PostInline.Text(text))),
            ),
        ),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private enum class CitingScenario(val fileName: String) {
        Loaded("loaded"),
        Loading("loading"),
        Empty("empty"),
    }
}
