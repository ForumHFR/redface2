package fr.forumhfr.redface2.feature.editor

import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.BbcodeTextField
import fr.forumhfr.redface2.core.ui.editor.EditorHeaderPanel
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers.ClassParameter
import org.robolectric.util.ReflectionHelpers.callInstanceMethod

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PostEditorTransientComposeTest {
    @get:Rule val compose = createComposeRule()
    private val host = transientHost()
    private val focus = FocusRequester()
    private lateinit var quotePanelView: View
    private lateinit var quoteInputMode: InputModeManager
    private val quotePanelLive = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion) and
        hasAnyAncestor(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Citations"))

    @Test fun `ED50 ED51 ED53 panel reorders removes announces and returns the same source selection`() {
        lateinit var inputMode: InputModeManager
        compose.setContent {
            RedfaceTheme {
                inputMode = LocalInputModeManager.current
                PostEditorTestContent(host.state, host::dispatch, host.body, host.events, host.memory)
            }
        }
        compose.onNode(hasSetTextAction()).requestFocus()
        val source = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        val selection = host.body.selection
        compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithContentDescription("Descendre la citation de Alice").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithContentDescription("Citation de Alice déplacée en position 2").assertExists()
        compose.runOnIdle { assertEquals(listOf("Bob", "Alice", "Chloé"), host.state.quotes.map { it.author }) }
        // Compose clickables only take focus outside touch mode (Focusability.SystemDefined), as in the
        // legacy QuoteCardsColumn: exercise ED-50 removal focus through keyboard mode and a semantic click.
        compose.onNodeWithContentDescription("Retirer la citation de Alice").performScrollTo()
        // The return value reflects the view's focus request, not the mode switch: assert the mode itself.
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        compose.onNodeWithContentDescription("Retirer la citation de Alice")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("Retirer la citation de Chloé").assertIsFocused()
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithContentDescription("Citation de Alice retirée. 2 citations restantes").assertExists()
        compose.onNodeWithText("Tout vider").performClick()
        compose.onNodeWithText("Aucune citation").assertIsFocused()
        compose.mainClock.advanceTimeBy(500L)
        compose.onNodeWithContentDescription("Toutes les citations ont été retirées").assertExists()
        compose.onNodeWithText("Tout vider").assertDoesNotExist()
        compose.onNodeWithText("Retour au message").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(source, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals("Bonjour", host.body.text.toString())
            assertFalse(host.calls.any { it is PostEditorIntent.SubmitClicked })
        }
    }

    @Test fun `ED50 removing the final card retains its announcement and gives focus to the empty panel`() {
        host.state = host.state.copy(quotes = host.state.quotes.take(1))
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        compose.onNodeWithText("Tout vider").assertDoesNotExist()
        compose.onNodeWithContentDescription("Retirer la citation de Alice").performScrollTo().performClick()
        compose.onNodeWithText("Aucune citation").assertIsFocused()
        compose.mainClock.advanceTimeBy(500L)
        assertPanelAnnouncement(id, "Citation de Alice retirée. 0 citation restante")
    }

    @Test fun `R2a every panel live region with cards survives Android accessibility occlusion pruning`() {
        openQuotePanel()
        val nodes = compose.onAllNodes(quotePanelLive, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        compose.runOnIdle {
            nodes.forEach { node ->
                // Unlike fetchSemanticsNode, Android's provider omits fully covered siblings.
                val info = quotePanelView.accessibilityNodeProvider.createAccessibilityNodeInfo(node.id)
                assertNotNull("Panel live region ${node.id} was pruned by Android", info)
                assertTrue(requireNotNull(info).isVisibleToUser)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)
            }
        }
        assertEquals("Only the panel owns a live region", 1, nodes.size)
    }

    @Test fun `R2a removal movement and clear all update the same Android panel live region`() {
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Retirer la citation de Bob")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(500L)
            assertPanelAnnouncement(id, "Citation de Bob retirée. 2 citations restantes")
            compose.onNodeWithContentDescription("Descendre la citation de Alice")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(500L)
            assertPanelAnnouncement(id, "Citation de Alice déplacée en position 2")
            compose.onNodeWithText("Tout vider").performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(500L)
            // Movement's minimum hold delays clear-all: this is its empty interval, not expiration.
            assertPanelAnnouncement(id, "")
            advanceQuotePanelTimeBy(320L)
            assertPanelAnnouncement(id, "Toutes les citations ont été retirées")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a removing the fifteenth scrolled card keeps the Android live region visible and updated`() {
        val template = host.state.quotes.first()
        host.state = host.state.copy(quotes = (1..15).map { index ->
            template.copy(locator = template.locator.copy(numreponse = 100 + index), author = "Auteur $index")
        })
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        val returnButton = compose.onNodeWithText("Retour au message")
        val titleBottom = compose.onNodeWithText("Citations").getUnclippedBoundsInRoot().bottom
        compose.onNodeWithContentDescription("Retirer la citation de Auteur 15").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500L)
        // The former live region followed Return: scrolling it past the pinned title must prune it.
        assertTrue("Return must scroll entirely above the title's bottom",
            returnButton.getUnclippedBoundsInRoot().bottom < titleBottom)
        assertPanelAnnouncement(id, "Citation de Auteur 15 retirée. 14 citations restantes")
    }

    @Test fun `R2a two identical moves expose an empty interval and the same announcement twice to Android`() {
        host.state = host.state.copy(quotes = host.state.quotes.take(2).map { it.copy(author = "Alice") })
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        val move = compose.onNode(hasContentDescription("Monter la citation de Alice") and isEnabled())
        compose.mainClock.autoAdvance = false
        try {
            move.performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(160L)
            assertPanelAnnouncement(id, "")
            advanceQuotePanelTimeBy(160L)
            assertPanelAnnouncement(id, "Citation de Alice déplacée en position 1")
            val first = host.state.quotes.first()
            // The other Alice now occupies position 2: same author, same result, different snapshot.
            // Queue it while the first message is still displayed, before its minimum dwell ends.
            move.performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(208L)
            assertPanelAnnouncement(id, "Citation de Alice déplacée en position 1")
            advanceQuotePanelTimeBy(128L)
            assertPanelAnnouncement(id, "Citation de Alice déplacée en position 1")
            advanceQuotePanelTimeBy(160L)
            assertPanelAnnouncement(id, "")
            advanceQuotePanelTimeBy(208L)
            assertPanelAnnouncement(id, "Citation de Alice déplacée en position 1")
            compose.runOnIdle { assertTrue(first != host.state.quotes.first()) }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a the idle Android live region is not focusable and still announces again after expiration`() {
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        assertPanelAnnouncement(id, "")
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Retirer la citation de Bob")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L)
            assertPanelAnnouncement(id, "Citation de Bob retirée. 2 citations restantes")
            advanceQuotePanelTimeBy(1_792L)
            assertPanelAnnouncement(id, "Citation de Bob retirée. 2 citations restantes")
            advanceQuotePanelTimeBy(320L)
            assertPanelAnnouncement(id, "")
            compose.onNodeWithText("Tout vider").performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(160L)
            assertPanelAnnouncement(id, "")
            advanceQuotePanelTimeBy(160L)
            assertPanelAnnouncement(id, "Toutes les citations ont été retirées")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a closing the panel clears the displayed message and discards queued announcements`() {
        openQuotePanel()
        val oldId = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Descendre la citation de Alice")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L)
            assertPanelAnnouncement(oldId, "Citation de Alice déplacée en position 2")
            compose.onNodeWithContentDescription("Descendre la citation de Bob")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelFrame() // Queue Bob while Alice is still displayed.
            assertPanelAnnouncement(oldId, "Citation de Alice déplacée en position 2")
            advanceQuotePanelTimeBy(768L)
            assertPanelAnnouncement(oldId, "Citation de Bob déplacée en position 2")
            // Prove Bob was emitted, then queue the already-observed Alice action behind it.
            compose.onNodeWithContentDescription("Descendre la citation de Alice")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelFrame()
            assertPanelAnnouncement(oldId, "Citation de Bob déplacée en position 2")
            compose.onNodeWithText("Retour au message").performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelFrame()
            compose.onNode(quotePanelLive, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelFrame()
            val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
            assertPanelAnnouncement(id, "")
            advanceQuotePanelTimeBy(1_024L)
            assertPanelAnnouncement(id, "")
            compose.onNodeWithText("Tout vider").performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L)
            assertPanelAnnouncement(id, "Toutes les citations ont été retirées")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a merged quote buttons omit glyph text and clear all uses its visible label once`() {
        openQuotePanel()
        listOf("Monter", "Descendre", "Retirer").forEach { action ->
            val label = "$action la citation de Alice"
            val node = compose.onNodeWithContentDescription(label).fetchSemanticsNode()
            assertFalse("$action must not merge its decorative glyph", node.config.contains(SemanticsProperties.Text))
            val unmerged = compose.onNodeWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("Label and clickable must share a layout node", node.id, unmerged.id)
            assertTrue(unmerged.config.contains(SemanticsActions.OnClick))
            assertTrue(unmerged.config.isMergingSemanticsOfDescendants)
            compose.runOnIdle {
                val nodes = quoteAccessibilityNodes(node.id)
                val button = nodes.first()
                assertTrue(button.isVisibleToUser && button.isImportantForAccessibility)
                assertTrue("The named group must be Android's clickable focus target",
                    button.isClickable && button.isScreenReaderFocusable)
                assertEquals("Only the button takes accessibility focus", 1, nodes.count { it.isScreenReaderFocusable })
                assertEquals("The group must expose the button role exactly once", 1,
                    nodes.count { it.className?.toString() == "android.widget.Button" })
                assertTrue(nodes.drop(1).none { it.isClickable })
                // Compose puts a merging parent's description on a virtual child to avoid clobbering
                // descendants. Android/TalkBack computes the button's name from that whole group.
                assertEquals(listOf(label), nodes.mapNotNull { it.contentDescription?.toString() }
                    .filter { it.isNotEmpty() })
                assertTrue("Neither the button nor its Android children may expose the glyph",
                    nodes.all { it.text.isNullOrEmpty() })
            }
        }
        val clear = compose.onNodeWithText("Tout vider").fetchSemanticsNode()
        assertEquals(listOf("Tout vider"), clear.config[SemanticsProperties.Text].map { it.text })
        assertFalse(clear.config.contains(SemanticsProperties.ContentDescription))
        compose.runOnIdle {
            val nodes = quoteAccessibilityNodes(clear.id)
            assertTrue(nodes.first().isClickable && nodes.first().isScreenReaderFocusable)
            assertEquals(listOf("Tout vider"), nodes.mapNotNull { it.text?.toString() }.filter { it.isNotEmpty() })
            assertTrue(nodes.all { it.contentDescription.isNullOrEmpty() })
        }
    }

    @Test fun `R2a an adapter filtered move cannot announce or steal focus when a card above is removed`() {
        val announcements = mutableListOf<String>()
        setQuoteCardsWithLiveAdapter(announcements)
        val up = compose.onNodeWithContentDescription("Monter la citation de Bob")
        up.requestFocus().assertIsFocused()
        compose.mainClock.autoAdvance = false
        try {
            up.performSemanticsAction(SemanticsActions.OnClick) { click ->
                // The adapter sees the new state immediately, before the UI can disable its buttons.
                host.state = host.state.copy(isInitializing = true)
                assertFalse(host.adapter.state.scaffold.controlsEnabled)
                assertTrue(click())
                assertTrue(host.calls.isEmpty())
                host.state = host.state.copy(isInitializing = false)
            }
            advanceQuotePanelFrame()
            compose.onNodeWithContentDescription("Retirer la citation de Alice")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L) // Before the pending move's one-second expiry.
            compose.runOnIdle {
                assertEquals(listOf("Bob", "Chloé"), host.state.quotes.map { it.author })
                assertEquals(listOf("Citation de Alice retirée. 2 citations restantes"), announcements)
                assertEquals(1, host.calls.size)
                assertTrue(host.calls.single() is PostEditorIntent.QuoteRemoved)
            }
            compose.onNodeWithContentDescription("Retirer la citation de Bob").assertIsFocused()
            compose.onNodeWithContentDescription("Descendre la citation de Bob").assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a an identical retry renews move expiry until its deferred reorder commits`() {
        val announcements = mutableListOf<String>()
        val requests = mutableListOf<PostEditorIntent>()
        compose.setContent { RedfaceTheme {
            PostEditorQuoteCards(host.state.quotes, true, requests::add, {}, announcements::add)
        } }
        val original = host.state.quotes
        val up = compose.onNodeWithContentDescription("Monter la citation de Bob")
        compose.mainClock.autoAdvance = false
        try {
            up.performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(768L)
            compose.runOnIdle {
                assertEquals(1, requests.size)
                requests.clear() // Filter the first intent without changing the rendered list.
            }
            up.performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(512L) // Past the first expiry, within the identical retry's lifetime.
            compose.runOnIdle {
                assertEquals(original, host.state.quotes)
                assertTrue(announcements.isEmpty())
                host.adapter.quoteIntent(requests.single())
            }
            advanceQuotePanelTimeBy(320L)
            compose.runOnIdle {
                assertEquals(listOf("Bob", "Alice", "Chloé"), host.state.quotes.map { it.author })
                assertEquals(listOf("Citation de Bob déplacée en position 1"), announcements)
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a opposite moves coalesced without a reorder expire before a later independent reorder`() {
        val announcements = mutableListOf<String>()
        setQuoteCardsWithLiveAdapter(announcements)
        val up = compose.onNodeWithContentDescription("Monter la citation de Bob").fetchSemanticsNode()
        val down = compose.onNodeWithContentDescription("Descendre la citation de Bob").fetchSemanticsNode()
        compose.onNodeWithText("Garder le focus").requestFocus().assertIsFocused()
        val original = host.state.quotes
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                assertTrue(requireNotNull(up.config[SemanticsActions.OnClick].action).invoke())
                assertTrue(requireNotNull(down.config[SemanticsActions.OnClick].action).invoke())
                assertEquals(original, host.state.quotes)
            }
            advanceQuotePanelTimeBy(1_200L)
            compose.runOnIdle {
                host.state = host.state.copy(quotes = listOf(original[1], original[0], original[2]))
            }
            advanceQuotePanelTimeBy(320L)
            compose.runOnIdle {
                assertEquals(2, host.calls.count { it is PostEditorIntent.QuoteMoved })
                assertTrue(announcements.isEmpty())
            }
            compose.onNodeWithText("Garder le focus").assertIsFocused()
            compose.onNodeWithContentDescription("Descendre la citation de Bob").assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a a neighbour move replaces a filtered request before expiry without focusing Bob at the top`() {
        val announcements = mutableListOf<String>()
        setQuoteCardsWithLiveAdapter(announcements)
        compose.onNodeWithText("Garder le focus").requestFocus().assertIsFocused()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithContentDescription("Monter la citation de Bob")
                .performSemanticsAction(SemanticsActions.OnClick) { click ->
                    host.state = host.state.copy(isInitializing = true)
                    assertFalse(host.adapter.state.scaffold.controlsEnabled)
                    assertTrue(click())
                    assertTrue(host.calls.isEmpty())
                    host.state = host.state.copy(isInitializing = false)
                }
            advanceQuotePanelFrame()
            compose.onNodeWithContentDescription("Descendre la citation de Alice")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L) // The filtered Bob request has not expired yet.
            compose.runOnIdle {
                assertEquals(listOf("Bob", "Alice", "Chloé"), host.state.quotes.map { it.author })
                assertEquals(listOf("Citation de Alice déplacée en position 2"), announcements)
                assertEquals(1, host.calls.count { it is PostEditorIntent.QuoteMoved })
            }
            compose.onNodeWithText("Garder le focus").assertIsFocused()
            compose.onNodeWithContentDescription("Descendre la citation de Bob").assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun `R2a a neighbour move replaces coalesced requests before expiry without focusing Bob at the bottom`() {
        val announcements = mutableListOf<String>()
        setQuoteCardsWithLiveAdapter(announcements)
        val up = compose.onNodeWithContentDescription("Monter la citation de Bob").fetchSemanticsNode()
        val down = compose.onNodeWithContentDescription("Descendre la citation de Bob").fetchSemanticsNode()
        compose.onNodeWithText("Garder le focus").requestFocus().assertIsFocused()
        val original = host.state.quotes
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                assertTrue(requireNotNull(up.config[SemanticsActions.OnClick].action).invoke())
                assertTrue(requireNotNull(down.config[SemanticsActions.OnClick].action).invoke())
                assertEquals(original, host.state.quotes)
            }
            advanceQuotePanelFrame()
            compose.onNodeWithContentDescription("Monter la citation de Chloé")
                .performSemanticsAction(SemanticsActions.OnClick)
            advanceQuotePanelTimeBy(320L) // The coalesced Bob request has not expired yet.
            compose.runOnIdle {
                assertEquals(listOf("Alice", "Chloé", "Bob"), host.state.quotes.map { it.author })
                assertEquals(listOf("Citation de Chloé déplacée en position 2"), announcements)
                assertEquals(3, host.calls.count { it is PostEditorIntent.QuoteMoved })
            }
            compose.onNodeWithText("Garder le focus").assertIsFocused()
            compose.onNodeWithContentDescription("Monter la citation de Bob").assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun setQuoteCardsWithLiveAdapter(announcements: MutableList<String>) {
        compose.setContent {
            quoteInputMode = LocalInputModeManager.current
            RedfaceTheme { Column {
                TextButton(onClick = {}) { Text("Garder le focus") }
                // Keep the rendered controls enabled to exercise the adapter's independent state guard.
                PostEditorQuoteCards(host.state.quotes, true, host.adapter::quoteIntent, {}, announcements::add)
            } }
        }
        compose.runOnIdle { quoteInputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, quoteInputMode.inputMode) }
    }

    @Test fun `R2a edge moves focus the other direction on the same card and interior moves keep focus`() {
        openQuotePanel()
        compose.runOnIdle { quoteInputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, quoteInputMode.inputMode) }
        val up = compose.onNodeWithContentDescription("Monter la citation de Bob")
        val down = compose.onNodeWithContentDescription("Descendre la citation de Bob")
        up.performScrollTo().requestFocus().assertIsFocused().performSemanticsAction(SemanticsActions.OnClick)
        up.assertIsNotEnabled()
        down.assertIsEnabled().assertIsFocused()
        compose.runOnIdle { assertEquals("Bob", host.state.quotes.first().author) }
        val downId = down.fetchSemanticsNode().id
        down.performSemanticsAction(SemanticsActions.OnClick)
        down.assertIsFocused()
        assertEquals(downId, down.fetchSemanticsNode().id)
        down.performSemanticsAction(SemanticsActions.OnClick)
        down.assertIsNotEnabled()
        up.assertIsEnabled().assertIsFocused()
        compose.runOnIdle { assertEquals("Bob", host.state.quotes.last().author) }
    }

    @Test fun `R2a two up clicks before recomposition focus down and announce the actual final position`() {
        val template = host.state.quotes.first()
        host.state = host.state.copy(quotes = listOf("Alice", "Bob", "Chloé", "Diane").mapIndexed { index, author ->
            template.copy(locator = template.locator.copy(numreponse = 101 + index), author = author)
        })
        openQuotePanel()
        val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
        compose.runOnIdle { quoteInputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, quoteInputMode.inputMode) }
        val up = compose.onNodeWithContentDescription("Monter la citation de Chloé")
        up.performScrollTo().requestFocus().assertIsFocused()
        compose.mainClock.autoAdvance = false
        try {
            // Invoke the SAME captured action twice in one UI turn: no idle/recomposition between clicks.
            up.performSemanticsAction(SemanticsActions.OnClick) { click ->
                assertTrue(click())
                assertEquals(listOf("Alice", "Chloé", "Bob", "Diane"), host.state.quotes.map { it.author })
                assertTrue(click())
            }
            advanceQuotePanelTimeBy(320L)
            compose.runOnIdle {
                assertEquals(listOf("Chloé", "Alice", "Bob", "Diane"), host.state.quotes.map { it.author })
            }
            up.assertIsNotEnabled()
            compose.onNodeWithContentDescription("Descendre la citation de Chloé").assertIsEnabled().assertIsFocused()
            assertPanelAnnouncement(id, "Citation de Chloé déplacée en position 1")
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun advanceQuotePanelTimeBy(milliseconds: Long) {
        // Semantic clicks write global snapshots; drain Android's apply notifications before ticking
        // the v2 dispatcher, or the recomposition and its effects may only be scheduled at the assertion.
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(milliseconds)
        compose.waitForIdle()
    }

    private fun advanceQuotePanelFrame() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun quoteAccessibilityNodes(id: Int): List<AccessibilityNodeInfo> {
        val info = requireNotNull(quotePanelView.accessibilityNodeProvider.createAccessibilityNodeInfo(id)) {
            "Quote accessibility node $id was pruned by Android"
        }
        // As in EditorEventControlTest: Robolectric getChild() supplies placeholders, not provider nodes.
        return listOf(info) + (0 until info.childCount).flatMap { index ->
            val childId = callInstanceMethod<Long>(info, "getChildId", ClassParameter.from(Int::class.java, index))
            quoteAccessibilityNodes((childId shr 32).toInt())
        }
    }

    private fun openQuotePanel() {
        compose.setContent {
            quotePanelView = LocalView.current
            quoteInputMode = LocalInputModeManager.current
            RedfaceTheme { PostEditorTestContent(host.state, host::dispatch, host.body, host.events, host.memory) }
        }
        val count = host.state.quotes.size
        val label = if (count == 1) "1 citation" else "$count citations"
        compose.onNodeWithContentDescription("$label, ouvrir le panneau citations").performClick()
    }

    private fun assertPanelAnnouncement(id: Int, text: String) {
        val node = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(id, node.id)
        if (text.isEmpty()) {
            assertFalse("An empty announcement must omit its description",
                node.config.contains(SemanticsProperties.ContentDescription))
        } else assertEquals(listOf(text), node.config[SemanticsProperties.ContentDescription])
        compose.runOnIdle {
            val info = requireNotNull(quotePanelView.accessibilityNodeProvider.createAccessibilityNodeInfo(id)) {
                "Panel live region $id was pruned by Android"
            }
            assertTrue("Panel live region $id is invisible to Android", info.isVisibleToUser)
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, info.liveRegion)
            // LiveRegion is an important accessibility key, even without any speakable content.
            assertTrue(info.isImportantForAccessibility)
            assertNull(info.text)
            if (text.isEmpty()) {
                assertNull("An empty announcement must have no Android description", info.contentDescription)
                assertFalse("An empty live region must not be a TalkBack stop", info.isScreenReaderFocusable)
                assertFalse(info.isFocusable)
            } else assertEquals(text, info.contentDescription?.toString())
        }
    }

    @Test fun `R2a the pinned live region preserves panel geometry with zero one or three cards`() =
        quotePanelHeaderGeometry()

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun `R2a the pinned live region preserves compact panel geometry with zero one or three cards`() =
        quotePanelHeaderGeometry()

    private fun quotePanelHeaderGeometry() {
        val baseline = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        val originalQuotes = host.state.quotes
        compose.setContent {
            quotePanelView = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                RedfaceTheme {
                    if (baseline.value) {
                        // Previous layout: the button alone in headerActions, 1 dp before the scroll content.
                        EditorHeaderPanel(true, "Citations", host.body, {}, {}, headerActions = {
                            if (host.state.quotes.size >= 2) TextButton(onClick = {}) { Text("Tout vider") }
                        }, body = {}) {
                            Spacer(Modifier.size(1.dp))
                            if (host.state.quotes.isEmpty()) Text("Aucune citation")
                            PostEditorQuoteCards(host.state.quotes, true, {}, {}, {})
                        }
                    } else PostEditorQuotePanel(host.adapter, host.body, {}, {})
                }
            }
        }
        compose.runOnIdle { host.adapter.open(PostEditorChromeSurface.Quotes) }
        listOf(1f, 1.3f).forEach { scale ->
            listOf(0, 1, 3).forEach { count ->
                compose.runOnIdle {
                    baseline.value = true
                    fontScale.value = scale
                    host.state = host.state.copy(quotes = originalQuotes.take(count))
                }
                val labels = listOf("Citations", "Retour au message") +
                    if (count == 0) listOf("Aucune citation") else listOf("❝ Alice — Extrait du message de Alice")
                val bounds = labels.associateWith { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
                val buttonBounds = if (count >= 2) compose.onNodeWithText("Tout vider").getUnclippedBoundsInRoot()
                    else null
                compose.runOnIdle { baseline.value = false }
                bounds.forEach { (label, expected) ->
                    assertEquals("$label at $count cards, fontScale=$scale", expected,
                        compose.onNodeWithText(label).getUnclippedBoundsInRoot())
                }
                if (buttonBounds != null) assertEquals(buttonBounds,
                    compose.onNodeWithText("Tout vider").getUnclippedBoundsInRoot())
                val id = compose.onNode(quotePanelLive, useUnmergedTree = true).fetchSemanticsNode().id
                assertPanelAnnouncement(id, "")
            }
        }
    }

    @Test fun `R2a portrait exposes quotes only in the panel without reducing the field`() = quoteGeometry()

    @Test
    @Config(qualifiers = "fr-rFR-w760dp-h360dp-land-xxhdpi")
    fun `R2a compact height keeps the same panel route and field geometry`() = quoteGeometry()

    private fun quoteGeometry() {
        compose.setContent { RedfaceTheme { PostEditorTestContent(host.state, host::dispatch,
            host.body, host.events, host.memory) } }
        val field = compose.onNodeWithTag("editor-scaffold-field")
        val bounds = field.getUnclippedBoundsInRoot()
        val node = compose.onNode(hasSetTextAction()).fetchSemanticsNode().id
        // QuoteCard renders the author and excerpt together in one Text node.
        compose.onNodeWithText("Extrait du message de Alice", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tout vider").assertDoesNotExist()
        compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations")
            .assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Extrait du message de Alice", substring = true).assertExists()
        compose.onNodeWithText("Tout vider").performClick()
        compose.onNodeWithText("Aucune citation").assertIsFocused()
        compose.onNodeWithText("Retour au message").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(node, compose.onNode(hasSetTextAction()).fetchSemanticsNode().id)
        assertEquals(bounds, field.getUnclippedBoundsInRoot())
        compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").assertDoesNotExist()
    }

    @Test fun `ED53 panel returns exactly once to an initially open keyboard`() = panelKeyboard(true)
    @Test fun `ED53 panel returns exactly once to an initially closed keyboard`() = panelKeyboard(false)

    private fun panelKeyboard(initiallyOpen: Boolean) {
        val input = TransientInput()
        val keyboard = TransientKeyboard()
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            input.Content {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    RedfaceTheme {
                        PostEditorTestContent(host.state, host::dispatch, host.body, host.events, host.memory)
                    }
                }
            }
        }
        val field = compose.onNode(hasSetTextAction())
        val node = field.fetchSemanticsNode().id
        val selection = TextRange(3, 1)
        listOf(initiallyOpen, !initiallyOpen).forEachIndexed { index, open ->
            if (open) field.performClick()
            // performTextInputSelection sends SetSelection(min, max), losing the range's orientation.
            // Set the live focused range directly, as in the ED86 and MP restoration tests.
            compose.runOnIdle {
                host.body.edit { this.selection = selection }
                input.insets(open)
            }
            field.assertIsFocused()
            compose.runOnIdle {
                assertEquals("Reversed range before opening the panel", selection, host.body.selection)
            }
            compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
            field.assertDoesNotExist()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).requestFocus().assertIsNotFocused()
            val keyboardBefore = keyboard.calls.toList()
            val sessions = compose.runOnIdle {
                assertEquals("Reversed range while the panel is open", selection, host.body.selection)
                assertNull(input.connection)
                input.insets(!open) // The return must use the opening snapshot, not these new insets.
                input.sessions
            }
            if (index == 0) compose.onNodeWithText("Retour au message").performClick()
            else compose.runOnIdle { back.onBackPressed() }
            field.assertIsFocused()
            assertEquals(node, field.fetchSemanticsNode().id)
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertEquals(keyboardBefore + if (open) listOf("show") else listOf("hide"), keyboard.calls)
                assertEquals(open, input.connection != null)
                assertEquals(sessions + if (open) 1 else 0, input.sessions)
                if (open) {
                    val connection = requireNotNull(input.connection)
                    val before = host.body.text.toString()
                    assertEquals(before.substring(selection.min, selection.max),
                        connection.getSelectedText(0)?.toString())
                    assertTrue(connection.commitText("IME", 1))
                    assertEquals(before.replaceRange(selection.min, selection.max, "IME"), host.body.text.toString())
                }
            }
        }
    }

    @Test fun `ED60 shared preview keeps the focused source instance selection and IME commands`() = preview(false)
    @Test fun `ED60 shared preview returns from a focused keyboard control without IME commands`() = preview(true)

    @Test fun `ED61 scaffold preview return restores selection without keyboard commands`() {
        val input = TransientInput()
        val keyboard = TransientKeyboard()
        mountScaffold(input, keyboard)
        val field = compose.onNode(hasSetTextAction())
        val node = field.fetchSemanticsNode().id
        val selection = TextRange(6, 1)
        listOf(false, true).forEach { open ->
            field.performClick()
            compose.runOnIdle {
                input.insets(open)
                quoteInputMode.requestInputMode(InputMode.Keyboard)
            }
            val toggle = compose.onNodeWithContentDescription("Aperçu")
            toggle.requestFocus().assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            val (sessions, commands) = compose.runOnIdle {
                host.body.edit { this.selection = selection }
                assertNull(input.connection)
                input.sessions to keyboard.calls.toList()
            }
            toggle.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
            field.assertIsFocused()
            assertEquals(node, field.fetchSemanticsNode().id)
            compose.onNodeWithTag("editor-preview-rendered").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertNull(input.connection)
                assertEquals(sessions, input.sessions)
                assertEquals(commands, keyboard.calls)
            }
        }
    }

    @Test fun `ED53 quote panel with shared preview restores an open keyboard`() =
        quotePanelWithPreview(covering = false, open = true)

    @Test fun `ED53 quote panel with shared preview restores a closed keyboard`() =
        quotePanelWithPreview(covering = false, open = false)

    @Test fun `ED61 quote panel with covering preview refuses focus and an open keyboard return`() =
        quotePanelWithPreview(covering = true, open = true)

    @Test fun `ED61 quote panel with covering preview refuses focus and a closed keyboard return`() =
        quotePanelWithPreview(covering = true, open = false)

    private fun mountScaffold(input: TransientInput, keyboard: TransientKeyboard, covering: () -> Boolean = { false }) {
        compose.setContent {
            quoteInputMode = LocalInputModeManager.current
            input.Content { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                RedfaceTheme {
                    // Keep normal-height chrome: shrinking only the content forces a covering preview.
                    Box(Modifier.height(if (covering()) 360.dp else 760.dp)) {
                        PostEditorTestContent(host.state, host::dispatch, host.body, host.events, host.memory)
                    }
                }
            } }
        }
    }

    private fun quotePanelWithPreview(covering: Boolean, open: Boolean) {
        val input = TransientInput()
        val keyboard = TransientKeyboard()
        val covered = mutableStateOf(false)
        mountScaffold(input, keyboard) { covered.value }
        val field = compose.onNode(hasSetTextAction())
        val node = field.fetchSemanticsNode().id
        val selection = TextRange(6, 1)
        field.performClick()
        compose.runOnIdle {
            input.insets(open)
            host.body.edit { this.selection = selection }
        }
        compose.onNodeWithContentDescription("Aperçu").performClick()
        compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
        compose.onNodeWithContentDescription("3 citations, ouvrir le panneau citations").performClick()
        field.assertDoesNotExist()
        val (sessions, commands) = compose.runOnIdle {
            assertEquals(open, host.memory.focus.imeBeforeSurface)
            assertEquals(selection, host.body.selection)
            assertNull(input.connection)
            // Reflow behind the panel; return must use the captured IME state, not the new insets.
            covered.value = covering
            input.insets(!open)
            input.sessions to keyboard.calls.toList()
        }
        compose.onNodeWithText("Retour au message").performScrollTo().performClick()
        if (covering) {
            compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
            compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
        } else {
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            field.assertIsFocused()
        }
        val expectedCommands = if (covering) commands else commands + if (open) listOf("show") else listOf("hide")
        compose.runOnIdle {
            assertTrue(host.state.isPreviewVisible)
            assertEquals(selection, host.body.selection)
            assertEquals(expectedCommands, keyboard.calls)
            assertEquals(!covering && open, input.connection != null)
            assertEquals(sessions + if (!covering && open) 1 else 0, input.sessions)
        }
        if (covering) {
            compose.runOnIdle { covered.value = false }
            compose.onNodeWithTag("editor-preview-rendered").assertIsDisplayed()
            field.assertIsNotFocused() // The refused surface return is consumed, never replayed on resize.
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertNull(input.connection)
                assertEquals(sessions, input.sessions)
                assertEquals(commands, keyboard.calls)
            }
            // Keyboard focus after the refused return must not start a session from the stale surface IME state.
            // The status chip is not a focus target; Tab into the body shares this focus path (showKeyboardOnFocus).
            compose.runOnIdle { quoteInputMode.requestInputMode(InputMode.Keyboard) }
            field.requestFocus().assertIsFocused()
            compose.runOnIdle {
                assertNull(input.connection)
                assertEquals(sessions, input.sessions)
                assertEquals(commands, keyboard.calls)
            }
            // A later explicit preview close has its own neutral return, even after a captured open IME.
            compose.onNodeWithContentDescription("Aperçu").requestFocus().assertIsFocused()
            compose.runOnIdle { host.body.edit { this.selection = selection } }
            compose.onNodeWithContentDescription("Aperçu").performKeyInput { pressKey(Key.Enter) }
            field.assertIsFocused()
            compose.runOnIdle {
                assertEquals(selection, host.body.selection)
                assertNull(input.connection)
                assertEquals(sessions, input.sessions)
                assertEquals(commands, keyboard.calls)
            }
        }
        assertEquals(node, compose.onNode(hasSetTextAction(), useUnmergedTree = covering).fetchSemanticsNode().id)
    }

    @Test fun `ED140 Tab reaches and toggles a real preview spoiler while the body stays unfocused`() {
        host.state = host.state.copy(quotes = emptyList(), preview = PostContent(listOf(
            PostBlock.Spoiler("Secret", PostContent(listOf(
                PostBlock.Paragraph(listOf(PostInline.Text("Contenu révélé"))),
            ))),
        )))
        lateinit var inputMode: InputModeManager
        lateinit var previewLabel: String
        compose.setContent {
            RedfaceTheme {
                inputMode = LocalInputModeManager.current
                previewLabel = stringResource(UiR.string.editor_preview_label)
                // Keep normal-height chrome, but constrain its content: the vertical split cannot
                // fit both panes' floors. Assert the covering fallback after its real measurement.
                Box(Modifier.size(360.dp, 360.dp)) {
                    EditorScaffold(host.body, EditorWindowMetrics(760.dp, 0.dp, 0.dp),
                        "Répondre", "Sujet de démonstration", host.adapter.chrome(), host.adapter.barActions,
                        host.adapter.submitState, host.adapter.submitActions, host.adapter.imageActions,
                        host.adapter::closeRequested, bodyFocusRequester = focus,
                        // Exercise PR 4's guard contract explicitly. R2b screens delegate covering
                        // to EditorPreview; their now-open scaffold guard cannot catch a scope regression.
                        bodyFocusRestoreAllowed = !host.state.isPreviewVisible,
                        fieldArea = { body ->
                            PostEditorPreview(host.state.isPreviewVisible, host.state.preview, host.body, focus, body)
                        })
                }
            }
        }
        compose.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        compose.runOnIdle { assertEquals(InputMode.Keyboard, inputMode.inputMode) }
        val toggle = compose.onNode(hasContentDescription(previewLabel) and
            hasAnyAncestor(hasTestTag("editor-scaffold-app-bar")))
        toggle.assertIsDisplayed().assertIsOff().requestFocus().assertIsFocused()
            .performKeyInput { pressKey(Key.Spacebar) }
        compose.runOnIdle { assertTrue(host.state.isPreviewVisible) }
        toggle.assertIsFocused().assertIsOn()
        compose.onNodeWithTag("post-editor-preview").assertIsDisplayed()
        compose.onNodeWithTag("editor-preview-fallback").assertIsDisplayed()
        compose.onNodeWithTag("editor-preview-divider-target").assertDoesNotExist()
        compose.onNodeWithText("Contenu révélé").assertDoesNotExist()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertFalse(it()) }.assertIsNotFocused()
        // With no quote/restore statuses, this is the last app-bar focus target. Tab enters fieldArea
        // to reach the preview's only clickable: guarding the whole slot would cancel that entry.
        toggle.assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        compose.onNodeWithText("Secret").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        compose.onNodeWithText("Contenu révélé").assertIsDisplayed()
        compose.onNodeWithText("Secret").assertIsFocused()
        compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsNotFocused()
    }

    private fun preview(fromControl: Boolean) {
        var mounts = 0
        var disposals = 0
        val keyboard = TransientKeyboard()
        val input = TransientInput()
        var navigate: () -> Unit = {}
        host.body.edit { selection = TextRange(6, 1) }
        compose.setContent {
            input.Content { CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                RedfaceTheme {
                    val inputMode = LocalInputModeManager.current
                    val focusManager = LocalFocusManager.current
                    navigate = {
                        inputMode.requestInputMode(InputMode.Keyboard)
                        focusManager.moveFocus(FocusDirection.Previous)
                    }
                    Column {
                        TextButton(onClick = { host.adapter.barActions.onPreview() }) { Text("Aperçu") }
                        PostEditorPreview(host.state.isPreviewVisible, PostContent(emptyList()), host.body, focus) {
                            DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                            BbcodeTextField(host.body, "Message", Modifier.fillMaxSize().focusRequester(focus),
                                showKeyboardOnFocus = false)
                        }
                    }
                }
            } }
        }
        compose.runOnIdle { focus.requestFocus() }
        if (!fromControl) compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(6, 1))
        compose.runOnIdle { input.insets(!fromControl) }
        val keyboardBefore = keyboard.calls.toList()
        val sessions = input.sessions
        if (fromControl) {
            compose.runOnIdle { navigate() }
            compose.onNodeWithText("Aperçu").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        } else compose.runOnIdle { host.adapter.barActions.onPreview() }
        val selection = host.body.selection
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNodeWithTag("editor-preview-divider-target").assertExists()
        compose.onNodeWithText("La prévisualisation s'affiche ici dès que vous saisissez du texte.").assertExists()
        compose.onNodeWithTag("post-editor-preview").performTouchInput { click(center) }
        if (fromControl) compose.onNodeWithText("Aperçu").assertIsFocused()
        compose.runOnIdle { host.adapter.back() }
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(selection, host.body.selection)
            assertEquals(1, mounts)
            assertEquals(0, disposals)
            assertEquals(keyboardBefore, keyboard.calls)
            assertEquals(!fromControl, input.connection != null)
            assertEquals(sessions, input.sessions)
        }
    }
}

private class TransientKeyboard : SoftwareKeyboardController {
    val calls = mutableListOf<String>()
    override fun show() { calls += "show" }
    override fun hide() { calls += "hide" }
}

/** Observe BTF2's real input session; the fixture never supplies return or keyboard callbacks. */
private class TransientInput {
    lateinit var view: View
    lateinit var density: Density
    var connection: InputConnection? = null
        private set
    var sessions = 0
        private set
    private val interceptor = PlatformTextInputInterceptor { request, next ->
        val current = request.createInputConnection(EditorInfo())
        connection = current
        sessions++
        try {
            next.startInputMethod(request)
        } finally {
            connection = null
            current.closeConnection()
        }
    }

    fun insets(visible: Boolean) {
        val bottom = with(density) { if (visible) 100.dp.roundToPx() else 0 }
        ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.ime(), visible).build())
    }

    @Composable
    fun Content(content: @Composable () -> Unit) {
        view = LocalView.current
        density = LocalDensity.current
        InterceptPlatformTextInput(interceptor) { content() }
    }
}

internal fun transientHost() = PostEditorChromeFixture().apply {
    state = state.copy(quotes = listOf("Alice", "Bob", "Chloé").mapIndexed { index, author ->
        QuoteSelection(QuoteLocator(1, 101 + index, null), author, "Extrait du message de $author")
    })
}
