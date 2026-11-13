package fr.forumhfr.redface2.core.ui.editor

import androidx.activity.ComponentDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.assertIsDisplayed
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `multiple versions are ordered by date and decisions identify the selected offer`() {
        val old = EditorDraftStore.DraftOffer("old", 1, EditorDraftStore.Draft("[b]Ancien[/b]", updatedAt = 1000))
        val recent = EditorDraftStore.DraftOffer("new", 2,
            EditorDraftStore.Draft("[quotemsg=101][b]Alice a écrit :[/b]secret[/quotemsg]", updatedAt = 2000))
        fixture.offers = listOf(old, recent)
        compose.setContent { fixture.Content() }
        status().performClick()
        compose.onNodeWithText("Citation de Alice").assertIsDisplayed()
        compose.onNodeWithText("Ancien").assertIsDisplayed()
        val first = compose.onAllNodes(hasText("Restaurer")).onFirst()
        first.performClick()
        compose.runOnIdle { assertEquals(listOf("restore:new"), fixture.calls) }
        status().performClick()
        compose.onAllNodes(hasText("Ignorer")).onFirst().performClick()
        compose.runOnIdle { assertEquals(listOf("restore:new", "discard:new"), fixture.calls) }
    }

    @Test fun `offer never opens automatically and its heading is readable`() {
        compose.setContent { fixture.Content() }
        compose.onNode(isDialog()).assertDoesNotExist()
        status().performClick()
        compose.onNodeWithText("Brouillon retrouvé")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Citation en attente").assertDoesNotExist()
        compose.runOnIdle { assertTrue(fixture.calls.isEmpty()) }
    }

    @Test fun `status returns body focus once for every dismissal and decision in normal and compact height`() {
        compose.setContent { fixture.Content() }
        var expectedReturns = 0
        listOf(false, true).forEach { compact ->
            compose.runOnIdle { fixture.compact = compact }
            status().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Brouillon à traiter"))
            listOf("close", "back", "swipe", "outside", "restore", "discard").forEach { closing ->
                compose.runOnIdle {
                    fixture.bodyFocus.requestFocus()
                    fixture.body.edit { selection = TextRange(6, 1) }
                }
                val before = fixture.calls.toList()
                status().performClick()
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
                    assertEquals(expectedReturns, fixture.returns)
                    assertEquals(TextRange(6, 1), fixture.body.selection)
                    assertTrue(fixture.available)
                    val decisions = listOf(closing).filter { it == "restore" || it == "discard" }
                    assertEquals(before + decisions, fixture.calls)
                    fixture.host.closeDraftOffer()
                    assertFalse(fixture.host.consumeReturn())
                }
            }
        }
    }

    @Test fun `losing an offer closes its sheet without a decision`() {
        compose.setContent { fixture.Content() }
        status().performClick()
        compose.runOnIdle { fixture.available = false }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(fixture.calls.isEmpty())
            assertEquals(1, fixture.returns)
        }
    }

    // The compact action bar only keeps ↺ beside a visible information when it is wide enough.
    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h400dp-xxhdpi")
    fun `ordinary information consumes the first offer tap in both bars`() {
        compose.setContent { fixture.Content() }
        listOf(false, true).forEach { compact ->
            compose.runOnIdle { fixture.compact = compact; fixture.notice() }
            status().performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            status().performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithContentDescription("Fermer l’offre de brouillon").performClick()
        }
    }

    @Test fun `restore and discard retain their independent guards`() {
        compose.setContent { fixture.Content() }
        compose.runOnIdle { fixture.restoreEnabled = false }
        status().performClick()
        compose.onNodeWithText("Restaurer").assertIsNotEnabled()
        compose.onNodeWithText("Ignorer").performClick()
        compose.runOnIdle { assertEquals(listOf("discard"), fixture.calls) }
    }

    private fun status() = compose.onNode(hasContentDescription("Offre de brouillon") and
        hasAnyAncestor(hasTestTag(if (fixture.compact) "editor-scaffold-action-bar" else "editor-scaffold-app-bar")))
        .revealEditorControl()
}
