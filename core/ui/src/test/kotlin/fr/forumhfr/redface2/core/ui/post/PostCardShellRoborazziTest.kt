package fr.forumhfr.redface2.core.ui.post

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.testing.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual control for the [PostCardShell] `flat` mode (#884) — one capture stacking the three
 * renderings on the same surface:
 *
 * - the default card (filled `surfaceContainer`, `shapes.medium`) — must stay the pre-#884 pixel
 *   rendering;
 * - `flat = true` — transparent, rectangular, closed by the `outlineVariant` hairline;
 * - `flat = true` + multi-quote border (#436) — the outline closes the post, NO hairline under it.
 *
 * References under `core/ui/src/test/screenshots/` are compared by default, including in CI.
 * Add `-Proborazzi.mode=record` only for an intentional visual update:
 *
 *     ./scripts/docker-dev.sh ./gradlew :core:ui:testDebugUnitTest \
 *         --tests '*PostCardShellRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class PostCardShellRoborazziTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun cardVersusFlatShell() {
        composeTestRule.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = "card (default)", style = MaterialTheme.typography.labelMedium)
                        SampleShell(flat = false)
                        Text(text = "flat", style = MaterialTheme.typography.labelMedium)
                        SampleShell(flat = true)
                        Text(
                            text = "flat + multi-quote border",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        SampleShell(
                            flat = true,
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/post_card_shell_card_vs_flat.png",
        )
    }

    @Composable
    private fun SampleShell(flat: Boolean, border: BorderStroke? = null) {
        PostCardShell(
            flat = flat,
            border = border,
            header = {
                Text(
                    text = "MonPseudo — 26/07/2026 10:00:00",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
            },
            body = {
                Text(
                    text = "Corps du post : un paragraphe simple pour comparer les deux modes.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            },
        )
    }
}
