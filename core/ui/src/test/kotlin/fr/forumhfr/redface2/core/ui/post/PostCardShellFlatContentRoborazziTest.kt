package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
 * #884 vague 3 (gardes Sol) — visual control of the FLAT (full-width) post rendering hosting the
 * three surface-bearing content blocks: a sourced quote, a spoiler and a code block. On the flat
 * transparent card those inner surfaces are the only figure/ground separation left — each capture
 * must show quote/spoiler/code keeping their own surfaces against the plain background, with the
 * hairline closing each post. Four record-only captures: dark standard, AMOLED, fontScale 2 and a
 * narrow 320.dp column.
 *
 * Record-only dump for the review (not a blocking golden). Run on demand (writes PNGs under
 * `core/ui/build/outputs/roborazzi/`, gitignored — see the header of
 * [PostRendererQuoteRoborazziTest] for why the Roborazzi Gradle plugin is not applied) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostCardShellFlatContentRoborazziTest*' --console=plain
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h1200dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostCardShellFlatContentRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun flatContentDark() {
        capture(darkTheme = true, amoled = false, name = "post_card_shell_flat_content_dark")
    }

    @Test
    fun flatContentAmoled() {
        capture(darkTheme = true, amoled = true, name = "post_card_shell_flat_content_amoled")
    }

    @Test
    fun flatContentFontScale2() {
        capture(
            darkTheme = true,
            amoled = false,
            fontScale = 2f,
            name = "post_card_shell_flat_content_fontscale2",
        )
    }

    @Test
    fun flatContentNarrow320() {
        capture(
            darkTheme = true,
            amoled = false,
            hostWidth = 320.dp,
            name = "post_card_shell_flat_content_narrow320",
        )
    }

    private fun capture(
        darkTheme: Boolean,
        amoled: Boolean,
        name: String,
        fontScale: Float = 1f,
        hostWidth: Dp = 360.dp,
    ) {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = darkTheme, amoledTheme = amoled, dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = fontScale),
                ) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        // Two flat posts stacked with NO gap — the list contract (#884): the shell
                        // hairline is the only separation between them.
                        Column(modifier = Modifier.width(hostWidth)) {
                            FlatPost(pseudo = "Lt Ripley", content = richContent())
                            FlatPost(pseudo = "tinc", content = followUpContent())
                        }
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    /**
     * A flat [PostCardShell] with the topic's inner gutters (header 12.dp, body 12/10.dp) so the
     * captures show the REAL figure/ground: full-bleed band + hairline, inset text and surfaces.
     */
    @Composable
    private fun FlatPost(pseudo: String, content: PostContent) {
        PostCardShell(
            flat = true,
            header = {
                PostIdentityBand(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        text = "$pseudo — 26/07/2026 10:00:00",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            },
            body = {
                PostRenderer(
                    content = content,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
                )
            },
        )
    }

    private fun richContent(): PostContent = PostContent(
        blocks = listOf(
            paragraph("Sur fond plat, la citation doit garder sa surface :"),
            PostBlock.Quote(
                author = "XaTriX",
                numreponse = 74749781,
                page = 8270,
                content = PostContent(
                    blocks = listOf(paragraph("Le mode pleine largeur retire la carte, pas les cadres.")),
                ),
            ),
            paragraph("Le spoiler aussi :"),
            PostBlock.Spoiler(
                label = null,
                content = PostContent(
                    blocks = listOf(paragraph("Contenu masqué derrière le spoiler.")),
                ),
            ),
            paragraph("Et le bloc de code :"),
            PostBlock.CodeBlock(
                text = "fun main() {\n    println(\"flat mode\")\n}",
                language = "kotlin",
            ),
        ),
    )

    private fun followUpContent(): PostContent = PostContent(
        blocks = listOf(
            paragraph("Post suivant, collé au premier : seule la hairline les sépare."),
        ),
    )

    private fun paragraph(text: String): PostBlock.Paragraph =
        PostBlock.Paragraph(inlines = listOf(PostInline.Text(text)))
}
