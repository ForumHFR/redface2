package fr.forumhfr.redface2.core.ui.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.theme.ReadingDisplaySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ED-133, D44: measured budget, real send button, system zoom 1.3 multiplied by preset L. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w392dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSubmissionSummaryLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun uncertainFitsWithoutOverflowOrEllipsis() =
        assertSummary(EditorSubmissionSummaryKind.Uncertain, "Envoi incertain")

    @Test
    fun draftWriteFailedFitsWithoutOverflowOrEllipsis() =
        assertSummary(EditorSubmissionSummaryKind.DraftWriteFailed, "Brouillon non écrit")

    private fun assertSummary(kind: EditorSubmissionSummaryKind, label: String) {
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 1.3f }
            val context = LocalContext.current.createConfigurationContext(configuration)
            // Actual Android resource configuration; L remains a separate multiplier in the theme.
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, fontScale = context.resources.configuration.fontScale),
            ) {
                RedfaceTheme(
                    darkTheme = false,
                    reading = ReadingDisplaySettings(fontScale = FontScalePreference.L),
                ) {
                    SummaryRow(kind)
                }
            }
        }
        assertEquals(360f, compose.onNodeWithTag("available").getUnclippedBoundsInRoot().width.value, 0.5f)
        val layout = textLayout(label)
        assertEquals("system fontScale", 1.3f, layout.layoutInput.density.fontScale, 0.001f)
        assertEquals("L scales labelSmall separately", 11f * 1.15f, layout.layoutInput.style.fontSize.value, 0.001f)
        assertEquals(label, 1, layout.lineCount)
        assertFalse("$label: visual overflow", layout.hasVisualOverflow)
        assertFalse("$label: ellipsis", layout.isLineEllipsized(0))
        assertEquals("$label: all characters laid out", label.length, layout.getLineEnd(0))
    }

    @Composable
    private fun SummaryRow(kind: EditorSubmissionSummaryKind) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().testTag("available"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorSubmissionSummary(kind, Modifier.weight(1f))
                // Same action and spacing as the current editor; no optimistic fixed button width.
                TextButton(onClick = {}) { Text("Masquer") }
                ArmedSubmitButton(
                    state = ArmedSubmitState(armed = false, enabled = true),
                    labels = ArmedSubmitLabels("Envoyer", "Confirmer"),
                    actions = ArmedSubmitActions({}, {}, {}),
                )
            }
        }
    }

    private fun textLayout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val read = requireNotNull(
            compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action,
        )
        assertTrue(read(layouts))
        assertEquals(1, layouts.size)
        return layouts.single()
    }
}
