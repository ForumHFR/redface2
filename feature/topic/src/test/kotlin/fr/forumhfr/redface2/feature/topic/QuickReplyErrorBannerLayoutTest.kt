package fr.forumhfr.redface2.feature.topic

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
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
    fun `Astra3 fullscreen control explains its disabled state until the offer decision`() {
        var state by mutableStateOf(QuickReplyUiState(restorableDraft = "D", quoteStorageUnavailable = false))
        var escalated = false
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                QuickReplyHeader(state, "Ouvrir l’éditeur plein écran") { escalated = true }
            }
        }
        compose.onNodeWithContentDescription("Ouvrir l’éditeur plein écran")
            .assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Choisissez « Restaurer » ou « Ignorer le brouillon » avant d’ouvrir l’éditeur plein écran.",
            ))
        assertFalse(escalated)
        compose.runOnIdle { state = state.copy(restorableDraft = null) }
        compose.onNodeWithContentDescription("Ouvrir l’éditeur plein écran").assertIsEnabled().performClick()
        assertTrue(escalated)
    }

    @Test
    fun `card quote failure offers a removable card without claiming submission is blocked`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false) { QuickReplyErrorBanner(EditorErrors.quoteFetchFailed(inline = false)) }
        }
        compose.onNodeWithText(
            "Citation non chargée. Réessayez l’envoi pour la charger à nouveau, " +
                "ou retirez sa carte pour envoyer sans elle.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Ignorer la citation").assertDoesNotExist()
    }

    @Test
    fun uncertainFits() = assertBanner(EditorErrors.uncertain, "Envoi incertain")

    @Test
    fun draftWriteFailedFits() = assertBanner(EditorErrors.draftWriteFailed, "Brouillon non écrit")

    @Test
    fun `quote failure detail explains reload and explicit ignore after two failures`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                QuickReplyErrorBanner(EditorErrors.quoteFetchFailed(inline = true, quickReply = true))
            }
        }
        compose.onNodeWithText(
            "Citation non chargée, envoi bloqué. Utilisez « Recharger ». Après deux échecs, " +
                "vous pourrez ignorer la citation pour envoyer votre texte seul.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertDoesNotExist()
    }

    @Test
    fun `dormant quote exposes both explicit decisions without submitting`() {
        val actions = mutableListOf<String>()
        val token = EditorDraftStore.AttemptToken("tester", "reply:23:35421", "attempt", 1)
        val state = QuickReplyUiState(
            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
            quoteStorageUnavailable = false,
            submission = EditorSubmissionState.Uncertain(token),
            submitError = EditorErrors.uncertain,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { QuickReplyStatus(state, callbacks(actions)) }
            }
        }
        compose.onNodeWithText("Après vérification, reprendre la citation").assertIsEnabled().performClick()
        compose.onNodeWithText("Ignorer la citation").assertIsEnabled().performClick()
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        assertEquals(listOf("resume", "ignore quote"), actions)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `offered body and failed quote have distinct ignore actions`() {
        val actions = mutableListOf<String>()
        val state = QuickReplyUiState(
            restorableDraft = "older body",
            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
            quoteFetchFailures = 2,
            quoteStorageUnavailable = false,
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { QuickReplyStatus(state, callbacks(actions)) }
            }
        }
        compose.onNodeWithText("Ignorer le brouillon").performClick()
        compose.onNodeWithText("Ignorer la citation").performClick()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.onNodeWithText("Recharger").assertDoesNotExist() // No GET behind the offer.
        assertEquals(listOf("ignore body", "ignore quote"), actions)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `two quote failures retain reload beside ignore`() {
        val actions = mutableListOf<String>()
        val state = QuickReplyUiState(
            pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
            quoteFetchFailures = 2,
            quoteStorageUnavailable = false,
            submitError = EditorErrors.quoteFetchFailed(inline = true, quickReply = true),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { QuickReplyStatus(state, callbacks(actions)) }
            }
        }
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        compose.onNodeWithText("Ignorer la citation").assertIsEnabled().performClick()
        assertEquals(listOf("reload", "ignore quote"), actions)
    }

    @Test
    fun `M4 reload stays hidden for the entire initial draft read`() {
        val actions = mutableListOf<String>()
        var state by mutableStateOf(QuickReplyUiState(isLoadingDraft = true))
        compose.setContent {
            RedfaceTheme(darkTheme = false) { Column { QuickReplyStatus(state, callbacks(actions)) } }
        }
        compose.onNodeWithText("Recharger").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoadingDraft = false, submitError = EditorErrors.draftSaveFailed) }
        compose.onNodeWithText("Recharger").assertIsEnabled().performClick()
        assertEquals(listOf("reload"), actions)
    }

    @Test
    fun `M1 unreadable sheet request has an explicit ignore without coordinates`() {
        val actions = mutableListOf<String>()
        val state = QuickReplyUiState(unreadableQuoteRequest = true, submitError = EditorErrors.unreadableQuoteRequest)
        compose.setContent {
            RedfaceTheme(darkTheme = false) { Column { QuickReplyStatus(state, callbacks(actions)) } }
        }
        compose.onNodeWithText("Ignorer la citation").assertIsEnabled().performClick()
        assertEquals(listOf("ignore quote"), actions)
    }

    @Test
    fun `Astra m3 legacy saver only suppression displays the transition notice`() {
        val actions = mutableListOf<String>()
        compose.setContent {
            RedfaceTheme(darkTheme = false) {
                Column { QuickReplyStatus(QuickReplyUiState(legacyQuoteRequestDropped = true), callbacks(actions)) }
            }
        }
        compose.onNodeWithText("Veuillez sélectionner à nouveau le message à citer.", substring = true)
            .assertIsDisplayed()
    }

    private fun callbacks(actions: MutableList<String>) = QuickReplyQuoteCallbacks(
        onReload = { actions += "reload" },
        onIgnore = { actions += "ignore quote" },
        onResume = { actions += "resume" },
        onRestoreDraft = { actions += "restore body" },
        onDiscardDraft = { actions += "ignore body" },
        onDismissLegacy = { actions += "dismiss legacy" },
    )

    private fun assertBanner(error: EditorError, label: String) {
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
        if (error == EditorErrors.draftWriteFailed) {
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
