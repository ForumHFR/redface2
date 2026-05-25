package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Diagnostic-only Roborazzi captures for the AMOLED quote rendering bug discussed on PR #207.
 *
 * On a real AMOLED device the quote card's `surfaceContainerHighest` (#1B1616) is visually
 * indistinguishable from `surface` (#000000), and a user reported that the 4dp accent bar
 * (`drawBehind` in [PostRenderer.QuoteFrame]) is also invisible. Wiping the topic Room cache
 * does not change the rendering — which invalidates the "stale AST" hypothesis and points at
 * a runtime rendering issue.
 *
 * These captures are not yet golden references gated in CI. They run on demand via :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostRendererQuoteRoborazziTest*' --console=plain --no-daemon
 *
 * The Roborazzi Gradle plugin is **not** applied (AGP 9 incompatibility, cf.
 * takahirom/roborazzi#781), so the usual `recordRoborazziDebug` task does not exist —
 * `roborazzi.test.record=true` is forced via `systemProperty` in `core/ui/build.gradle.kts`
 * so the plain `:core:ui:testDebugUnitTest` task is enough to write the PNGs. Output lands
 * under `core/ui/build/outputs/roborazzi/` (gitignored). The intent is a fast visual
 * feedback loop for the AMOLED quote diagnostic without having to flash the AAB to a device
 * between every iteration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererQuoteRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun postRendererQuoteAmoledSimple() {
        composeTestRule.setContent {
            AmoledHost {
                PostRenderer(content = simpleQuoteContent())
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/post_renderer_quote_amoled_simple.png",
        )
    }

    @Test
    fun postRendererQuoteAmoledNested() {
        composeTestRule.setContent {
            AmoledHost {
                PostRenderer(content = nestedQuoteContent())
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/post_renderer_quote_amoled_nested.png",
        )
    }

    /**
     * Light-theme counterpart for the simple quote — same `PostContent`, different theme. Lets us
     * confirm at a glance that the bug is AMOLED-specific (light/dark non-AMOLED look fine on the
     * device) without needing a second device run.
     */
    @Test
    fun postRendererQuoteLightControl() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Box(
                        modifier = Modifier
                            .width(360.dp)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(16.dp),
                    ) {
                        PostRenderer(content = simpleQuoteContent())
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/post_renderer_quote_light_simple.png",
        )
    }

    @androidx.compose.runtime.Composable
    private fun AmoledHost(content: @androidx.compose.runtime.Composable () -> Unit) {
        RedfaceTheme(
            darkTheme = true,
            amoledTheme = true,
            dynamicColor = false,
        ) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Box(
                    modifier = Modifier
                        .width(360.dp)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(16.dp),
                ) {
                    content()
                }
            }
        }
    }

    private fun simpleQuoteContent(): PostContent = PostContent(
        blocks = listOf(
            paragraph("Tu vois bien que la bordure du quote est invisible sur AMOLED."),
            PostBlock.Quote(
                author = "Lt Ripley",
                numreponse = null,
                page = null,
                content = PostContent(
                    blocks = listOf(
                        paragraph("J'ai filé un exemple sur l'issue GitHub."),
                    ),
                ),
            ),
            paragraph("Du coup c'est plié, on bouge la bordure dans le composable QuoteFrame."),
        ),
    )

    private fun nestedQuoteContent(): PostContent = PostContent(
        blocks = listOf(
            paragraph("Reprise du fil pour les nouveaux :"),
            PostBlock.Quote(
                author = "XaTriX",
                numreponse = null,
                page = null,
                content = PostContent(
                    blocks = listOf(
                        paragraph("Le rendu actuel a un souci côté AMOLED."),
                        PostBlock.Quote(
                            author = "Lt Ripley",
                            numreponse = null,
                            page = null,
                            content = PostContent(
                                blocks = listOf(
                                    paragraph("Confirmé ici sur Pixel 8 Pro, thème AMOLED, v60."),
                                ),
                            ),
                        ),
                        paragraph("Je propose qu'on capture le rendu via Roborazzi pour itérer."),
                    ),
                ),
            ),
            paragraph("OK on part là-dessus."),
        ),
    )

    private fun paragraph(text: String): PostBlock.Paragraph =
        PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))
}
