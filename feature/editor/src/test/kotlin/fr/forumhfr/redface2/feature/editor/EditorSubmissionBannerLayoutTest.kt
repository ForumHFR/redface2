package fr.forumhfr.redface2.feature.editor

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorError
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
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

/**
 * #1301-ST / ED-133: real post/topic error banners at 360 dp, system fontScale 1.3 × preset L.
 * The separate core:ui EditorErrorSummaryLayoutTest owns the 150 dp text contract of ED-133.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorSubmissionBannerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun postUncertainFits() = assertPost(EditorErrors.uncertain, "Envoi incertain")

    @Test
    fun postDraftWriteFailedFits() = assertPost(EditorErrors.draftWriteFailed, "Brouillon non écrit")

    @Test
    fun topicUncertainFits() = assertTopic(EditorErrors.uncertain, "Envoi incertain")

    @Test
    fun topicDraftWriteFailedFits() = assertTopic(EditorErrors.draftWriteFailed, "Brouillon non écrit")

    private fun assertPost(error: EditorError, label: String) {
        val state = PostEditorState(
            mode = PostEditorMode.Reply,
            cat = 23,
            topicId = 1301,
            numreponse = null,
            page = 1,
            subcat = 0,
            submitError = error,
        )
        assertBanner("post", label) { PostEditorTestContent(state) }
        compose.onNodeWithTag("editor-bar-event").performClick()
        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNodeWithTag("editor-scaffold-field").assertExists()
    }

    private fun assertTopic(error: EditorError, label: String) {
        val state = TopicFormState(
            mode = TopicFormMode.New,
            cat = 23,
            subcat = null,
            topicId = null,
            page = null,
            numreponse = null,
            submitError = error,
        )
        assertBanner("topic", label) { TopicFormErrorBanners(state = state, onIntent = {}) }
    }

    private fun assertBanner(surface: String, label: String, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply { fontScale = 1.3f }
            val context = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(density.density, context.resources.configuration.fontScale),
            ) {
                RedfaceTheme(
                    darkTheme = false,
                    reading = ReadingDisplaySettings(fontScale = FontScalePreference.L),
                ) {
                    // PostEditorContent and TopicFormContent: 16 dp gutters, then the controls column.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                    }
                }
            }
        }
        if (surface == "post") {
            compose.onNodeWithContentDescription("$label, Afficher les détails").assertIsDisplayed()
            return
        }
        assertSummary(surface, label)
        val summaryBounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
        val dismissBounds = compose.onNodeWithText("Masquer").getUnclippedBoundsInRoot()
        assertTrue("$surface: dismiss follows summary vertically", dismissBounds.top >= summaryBounds.bottom)
    }

    private fun assertSummary(surface: String, label: String) {
        val layout = textLayout(label)
        val density = layout.layoutInput.density.density
        val available = layout.layoutInput.constraints.maxWidth / density
        val intrinsic = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density
        val message = "$surface / $label: intrinsic=${intrinsic}dp, available=${available}dp"
        assertEquals("$message: screen width", 360f, compose.onRoot().getUnclippedBoundsInRoot().width.value, 0.5f)
        assertEquals("$message: xxhdpi density", 3f, density, 0.001f)
        assertEquals("$message: system fontScale", 1.3f, layout.layoutInput.density.fontScale, 0.001f)
        assertEquals(
            "$message: preset L", 11f * FontScalePreference.L.factor, layout.layoutInput.style.fontSize.value, 0.001f,
        )
        assertEquals("$message: text budget after gutters and icon", 308f, available, 0.5f)
        assertTrue("$message: intrinsic width exceeds budget", intrinsic <= available)
        assertEquals("$message: one line", 1, layout.lineCount)
        // Text may measure to its content while MultiParagraph retains the full constraint.
        // hasVisualOverflow compares those different widths and is not a clipping oracle here.
        val lineRight = layout.getLineRight(0) / density
        assertTrue("$message: lineRight=${lineRight}dp", lineRight <= available)
        assertFalse("$message: max lines exceeded", layout.multiParagraph.didExceedMaxLines)
        assertFalse("$message: ellipsis", layout.isLineEllipsized(0))
        assertEquals("$message: all characters laid out", label.length, layout.getLineEnd(0))
    }

    private fun textLayout(label: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        val read = requireNotNull(
            compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action,
        ) { "$label: Text must expose its layout" }
        assertTrue("$label: layout action", read(layouts))
        assertEquals("$label: layout count", 1, layouts.size)
        return layouts.single()
    }
}
