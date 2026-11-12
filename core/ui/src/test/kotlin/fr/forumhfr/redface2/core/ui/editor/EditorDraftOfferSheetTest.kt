package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.ComponentDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextRange
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorDraftOfferSheetTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = EditorDraftOfferFixture()

    @Test fun `no automatic opening and the optional notice and heading are readable`() {
        compose.setContent { fixture.Content() }
        compose.onNode(isDialog()).assertDoesNotExist()
        open(throughEvent = false)
        compose.onNodeWithText("Brouillon retrouvé").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Un brouillon non envoyé a été retrouvé pour ce message.").assertIsDisplayed()
        compose.onNodeWithText("Citation en attente").assertIsDisplayed()
        compose.runOnIdle { fixture.pendingQuote = null }
        compose.onNodeWithText("Citation en attente").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun `both entries return body focus exactly once for every close and decision`() {
        compose.setContent { fixture.Content() }
        var expectedReturns = 0
        listOf(false, true).forEach { throughEvent ->
            listOf("close", "back", "swipe", "outside", "restore", "discard").forEach { closing ->
                compose.runOnIdle {
                    fixture.bodyFocus.requestFocus()
                    fixture.body.edit { selection = TextRange(6, 1) }
                }
                val callsBefore = fixture.calls.toList()
                open(throughEvent)
                when (closing) {
                    "close" -> compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
                    "back" -> compose.runOnIdle {
                        (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
                    }
                    "swipe" -> compose.onNodeWithTag("editor-draft-offer-sheet").performTouchInput { swipeDown() }
                    "outside" -> compose.onNode(isDialog()).performTouchInput { click(Offset(4f, 4f)) }
                    "restore" -> compose.onNodeWithText("Restaurer").performScrollTo().performClick()
                    "discard" -> compose.onNodeWithText("Ignorer").performScrollTo().performClick()
                }
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNode(hasSetTextAction()).assertIsFocused()
                expectedReturns++
                compose.runOnIdle {
                    assertEquals("$throughEvent / $closing", expectedReturns, fixture.returns)
                    assertEquals("Bonjour", fixture.body.text.toString())
                    assertEquals(TextRange(6, 1), fixture.body.selection)
                    assertTrue(fixture.available)
                    assertNull(fixture.events.state.visibleEvent)
                    assertNull(fixture.events.state.detailKey)
                    val decision = listOf(closing).filter { it == "restore" || it == "discard" }
                    assertEquals(callsBefore + decision, fixture.calls)
                    fixture.host.closeDraftOffer() // Late/duplicate dismissal must not schedule a second return.
                }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals(expectedReturns, fixture.returns) }
            }
        }
    }

    @Test fun `losing the offer closes either entry even after the detail delivery disappears`() {
        compose.setContent { fixture.Content() }
        listOf(false, true).forEachIndexed { index, throughEvent ->
            compose.runOnIdle { fixture.available = true }
            open(throughEvent)
            compose.runOnIdle {
                fixture.host.detail?.key?.let { fixture.events.dispatch(EditorEventInput.Acknowledge(it)) }
                fixture.available = false
            }
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertFalse(fixture.host.draftOfferOpen)
                assertNull(fixture.host.detail)
                assertNull(fixture.events.state.detailKey)
                assertEquals(index + 1, fixture.returns)
                assertTrue(fixture.calls.isEmpty())
            }
        }
    }

    @Test fun `closing without a decision preserves the pending send guard and allows reopening`() {
        fixture.state = fixture.state.copy(sendActivatable = false, postAllowed = false)
        compose.setContent { fixture.Content() }
        open(throughEvent = true)
        compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
        compose.runOnIdle {
            assertFalse(fixture.host.submitState.enabled)
            assertEquals(EditorSubmissionState.Ready, fixture.state.submission)
            assertTrue(fixture.available)
            assertTrue(fixture.calls.isEmpty())
        }
        open(throughEvent = false)
        compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
    }

    @Test fun `restore and discard retain their separate enabled states`() {
        fixture.restoreEnabled = false
        compose.setContent { fixture.Content() }
        open(throughEvent = false)
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").performClick()
        compose.runOnIdle {
            assertEquals(listOf("discard"), fixture.calls)
            fixture.restoreEnabled = true
            fixture.discardEnabled = false
        }
        open(throughEvent = false)
        compose.onNodeWithText("Ignorer").assertIsNotEnabled()
        compose.onNodeWithText("Restaurer").performClick()
        compose.runOnIdle { assertEquals(listOf("discard", "restore"), fixture.calls) }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h400dp-xxhdpi")
    fun `first offer tap opens under its information in app bar and compact action bar only when enabled`() {
        compose.setContent { fixture.Content() }
        listOf(false, true).forEach { compact ->
            listOf(false, true).forEach { enabled ->
                compose.runOnIdle {
                    fixture.compact = compact
                    fixture.offerEventEnabled = enabled
                    fixture.notice()
                }
                status().performClick()
                if (enabled) {
                    compose.onNode(isDialog()).assertExists()
                    compose.runOnIdle { assertNull(fixture.events.state.visibleEvent) }
                    compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
                } else {
                    compose.onNode(isDialog()).assertDoesNotExist()
                    compose.runOnIdle { assertNull(fixture.events.state.visibleEvent) }
                    status().performClick() // Ordinary ED-44 still needs the second tap.
                    compose.onNode(isDialog()).assertExists()
                    compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
                }
            }
        }
    }

    @Test fun `unrelated information still consumes the first offer tap when the exception is enabled`() {
        compose.setContent { fixture.Content() }
        compose.runOnIdle { fixture.notice(offer = false) }
        status().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle { assertNull(fixture.events.state.visibleEvent) }
    }

    private fun open(throughEvent: Boolean) {
        if (throughEvent) {
            compose.runOnIdle { fixture.notice() }
            compose.onNodeWithContentDescription("Brouillon retrouvé, Afficher les détails").performClick()
        } else status().performClick()
        compose.onNode(isDialog()).assertExists()
    }

    private fun status() = compose.onNode(hasContentDescription("Offre de brouillon") and
        hasAnyAncestor(hasTestTag(if (fixture.compact) "editor-scaffold-action-bar" else "editor-scaffold-app-bar")))
        .revealEditorControl()
}
