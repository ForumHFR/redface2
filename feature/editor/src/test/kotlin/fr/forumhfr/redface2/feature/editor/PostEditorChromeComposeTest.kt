package fr.forumhfr.redface2.feature.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.domain.editor.EditorSubmissionState
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val host = PostEditorChromeFixture()
    private val focus = FocusRequester()
    private val scroll = ScrollState(0)

    @Composable private fun Content() {
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
            EditorScaffold(host.body, EditorWindowMetrics(740.dp, 24.dp, 30.dp), "Répondre", "Sujet",
                host.adapter.chrome(), host.adapter.barActions, host.adapter.submitState, host.adapter.submitActions,
                host.adapter.imageActions, host.adapter::closeRequested, bodyFocusRequester = focus,
                bodyScrollState = scroll, overlay = { restore, body ->
                    PostEditorChromeSurfaces(host.adapter, host.body, smileys, restore, body)
                })
        }
    }

    @Test fun `ED22 ED44 ED80 selection survives tools and typing then only the second tap sends`() {
        compose.setContent { Content() }
        compose.runOnIdle { focus.requestFocus() }
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(6, 1))
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Gras").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        val selection = host.body.selection
        compose.runOnIdle { assertEquals("onjou", host.body.text.substring(selection.min, selection.max)) }
        // Return to Normal, then exercise the real options sheet and its expanded 16-tone grid.
        compose.onNodeWithContentDescription("Mise en forme").performClick()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithTag("${MSG_ICON_OPTION_TAG_PREFIX}16").assertExists()
        compose.runOnIdle { host.adapter.closeSurface() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(selection, host.body.selection) }
        val button = compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot()
        repeat(50) { compose.onNode(hasSetTextAction()).performTextInput("a") }
        assertEquals(button, compose.onNodeWithTag("editor-bar-submit").getUnclippedBoundsInRoot())
        compose.runOnIdle {
            assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked })
            host.error(EditorErrors.noImageReceived)
        }
        compose.onNodeWithTag("editor-bar-submit").performClick()
        compose.runOnIdle { assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked }) }
        compose.onNodeWithTag("editor-bar-submit").performClick()
        compose.runOnIdle { assertEquals(1, host.calls.count { it is PostEditorIntent.SubmitClicked }) }
    }

    @Test fun `ED45 details reload and hide return focus without any submission acknowledgement`() {
        host.error(EditorErrors.formNetwork)
        compose.setContent { Content() }
        compose.runOnIdle { focus.requestFocus() }
        val selection = host.body.selection
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        compose.onNodeWithContentDescription("Hors connexion, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(node, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls)
            host.state = host.state.copy(pendingQuotes = listOf(QuoteSelection(QuoteLocator(1, 101, null), "", "")),
                submission = EditorSubmissionState.Uncertain(null))
            host.error(EditorErrors.uncertain, operation = 2)
        }
        compose.onNodeWithContentDescription("Envoi incertain, Afficher les détails").performClick()
        compose.onNodeWithText("Ignorer la citation").assertExists()
        compose.onNodeWithText("Après vérification, reprendre la citation").assertExists()
        compose.onNodeWithText("Masquer").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { assertEquals(listOf(PostEditorIntent.ReloadRequested), host.calls) }
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNodeWithText("Ignorer la citation").performClick()
        compose.onNodeWithContentDescription("1 citation, ouvrir le panneau citations").performClick()
        compose.onNodeWithText("Après vérification, reprendre la citation").performClick()
        compose.runOnIdle {
            assertEquals(listOf(PostEditorIntent.ReloadRequested, PostEditorIntent.PendingQuoteIgnored,
                PostEditorIntent.PendingQuoteResumed), host.calls)
        }
    }

    @Test fun `ED13 ED14 read-only programmatic upload insertion reveals final caret without stealing selection`() {
        host.state = host.state.copy(isUploading = true)
        compose.setContent { Content() }
        compose.runOnIdle {
            host.body.edit {
                replace(0, length, (1..80).joinToString("\n") { "Image insérée $it" })
                selection = TextRange(length)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(scroll.value > 0)
            assertEquals(host.body.text.length, host.body.selection.end)
            assertFalse(host.adapter.submitState.enabled)
            assertTrue(host.calls.isEmpty())
        }
    }
}
