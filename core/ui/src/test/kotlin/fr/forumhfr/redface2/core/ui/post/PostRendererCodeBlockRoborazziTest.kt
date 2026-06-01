package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual captures for the `[code]` line-number gutter (#244 follow-up).
 *
 * HFR renders `[code]` with a left line-number gutter; [CodeWithLineNumbers] replicates it so the
 * soft-wrap introduced in #244 stays unambiguous — exactly one number per LOGICAL line, and a
 * wrapped continuation visual line gets NO number. These captures prove:
 *
 * - [codeBlockWrappingLine] : a deliberately over-long line 2 soft-wraps onto a second visual line
 *   that carries no number (1, 2, 3, 4 stay aligned to the logical lines).
 * - [codeBlockManyLines]    : >9 lines, so the gutter widens to two digits and numbers stay
 *   right-aligned without shifting the code column.
 *
 * Run on demand (writes PNGs under `core/ui/build/outputs/roborazzi/`, gitignored — see the header
 * of [PostRendererQuoteRoborazziTest] for why the Roborazzi Gradle plugin is not applied) :
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostRendererCodeBlockRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostRendererCodeBlockRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun codeBlockWrappingLine() {
        composeTestRule.setContent {
            LightHost {
                PostRenderer(content = wrappingCodeContent())
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/post_renderer_code_wrapping.png",
        )
    }

    @Test
    fun codeBlockManyLines() {
        composeTestRule.setContent {
            LightHost {
                PostRenderer(content = manyLineCodeContent())
            }
        }
        composeTestRule.onRoot().captureRoboImage(
            filePath = "build/outputs/roborazzi/post_renderer_code_many_lines.png",
        )
    }

    @Composable
    private fun LightHost(content: @Composable () -> Unit) {
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
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

    private fun wrappingCodeContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.CodeBlock(
                text = buildString {
                    appendLine("fun greet(name: String) {")
                    appendLine(
                        "    val message = \"This is a deliberately very long line that has to " +
                            "exceed the card width on a 360dp screen so it soft-wraps onto a " +
                            "second visual line with no line number\"",
                    )
                    appendLine("    println(message)")
                    append("}")
                },
                language = "kotlin",
            ),
        ),
    )

    private fun manyLineCodeContent(): PostContent = PostContent(
        blocks = listOf(
            PostBlock.CodeBlock(
                text = (1..12).joinToString(separator = "\n") { index ->
                    "line $index of the snippet"
                },
                language = null,
            ),
        ),
    )
}
