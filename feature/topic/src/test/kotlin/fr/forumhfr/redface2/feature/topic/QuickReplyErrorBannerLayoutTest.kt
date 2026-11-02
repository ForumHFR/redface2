package fr.forumhfr.redface2.feature.topic

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
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

/**
 * #1301-ST / ED-133: the real sheet banner at 360 dp, system fontScale 1.3 × preset L.
 * La largeur de la future barre (≈ 150 dp d'ED-133) est intenable à 1,3 × L et relève de R0b (#1456).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickReplyErrorBannerLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun uncertainFits() = assertBanner(QuickReplySubmitError.Uncertain, "Envoi incertain")

    @Test
    fun draftWriteFailedFits() = assertBanner(QuickReplySubmitError.DraftWriteFailed, "Brouillon non écrit")

    private fun assertBanner(error: QuickReplySubmitError, label: String) {
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
                    // QuickReplySheet: 16 dp gutters and a fields column with 12 dp child spacing.
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickReplyErrorBanner(error)
                    }
                }
            }
        }
        assertSummary(label)
        val detail = compose.onNodeWithText("Votre message a peut-être été envoyé", substring = true)
        if (error == QuickReplySubmitError.DraftWriteFailed) {
            detail.assertDoesNotExist()
        } else {
            val detailBounds = detail.assertIsDisplayed().getUnclippedBoundsInRoot()
            val summaryBounds = compose.onNodeWithText(label).getUnclippedBoundsInRoot()
            assertTrue(
                "sheet: verification detail follows summary vertically", detailBounds.top >= summaryBounds.bottom,
            )
        }
    }

    private fun assertSummary(label: String) {
        val layout = textLayout(label)
        val density = layout.layoutInput.density.density
        val available = layout.layoutInput.constraints.maxWidth / density
        val intrinsic = layout.multiParagraph.intrinsics.maxIntrinsicWidth / density
        val message = "sheet / $label: intrinsic=${intrinsic}dp, available=${available}dp"
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
