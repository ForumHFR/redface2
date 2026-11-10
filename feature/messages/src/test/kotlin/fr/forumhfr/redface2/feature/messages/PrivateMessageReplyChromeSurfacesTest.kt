package fr.forumhfr.redface2.feature.messages

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.request.ImageResult
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.R as UiR
import fr.forumhfr.redface2.core.ui.editor.EditorBarGesture
import fr.forumhfr.redface2.core.ui.editor.EditorBarMode
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import java.util.concurrent.atomic.AtomicReference
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

/** R2 entry conditions exercised through the reply's production scaffold and overlays. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageReplyChromeSurfacesTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageReplyChromeFixture()

    @Test fun `restore panel wires both remedies and returns focus without a second sheet return`() {
        f.state = f.state.copy(restorableDraft = "Brouillon privé")
        content()
        listOf("Restaurer" to "restore", "Ignorer" to "discard").forEachIndexed { index, (label, call) ->
            compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            compose.onNodeWithText(label).performClick()
            compose.onNode(hasSetTextAction()).assertIsFocused()
            compose.runOnIdle {
                assertEquals(call, f.calls[index])
                assertEquals(index + 1, f.calls.size)
                assertNull(f.adapter.surface)
                assertEquals(0, f.adapter.returnGeneration)
                assertFalse(f.adapter.consumeReturn())
            }
        }
    }

    @Test
    @Config(qualifiers = "fr-rFR-w360dp-h760dp-port-xxhdpi")
    fun `restore panel states its offer once and never ellipsizes it at font scale 1_3`() {
        f.state = f.state.copy(restorableDraft = "Brouillon privé")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density = density.density, fontScale = 1.3f)) {
                RedfaceTheme { PrivateMessageReplyTestContent(f.state, f.body, f.actions, f.events, f.memory) }
            }
        }
        compose.onNodeWithContentDescription("Brouillon retrouvé, restaurer ou ignorer").performClick()
        compose.onNodeWithText("Brouillon retrouvé").assertIsDisplayed()
        val offer = "Un brouillon non envoyé a été retrouvé."
        compose.onAllNodesWithText(offer, useUnmergedTree = true).assertCountEquals(1)
        val layouts = mutableListOf<TextLayoutResult>()
        val read = requireNotNull(compose.onNodeWithText(offer, useUnmergedTree = true).fetchSemanticsNode()
            .config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
        assertTrue(read(layouts))
        val layout = layouts.single()
        val last = layout.lineCount - 1
        assertEquals(1.3f, layout.layoutInput.density.fontScale, 0.001f)
        assertFalse("The offer must not exceed a line cap", layout.multiParagraph.didExceedMaxLines)
        assertFalse("The offer must not be ellipsized", layout.isLineEllipsized(last))
        assertEquals("Every character must be laid out", offer.length, layout.getLineEnd(last, visibleEnd = true))
    }

    @Test fun `a full-screen form failure closes the open sheet instead of restoring it after the retry`() {
        content()
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onNodeWithText("Activer la signature").assertExists()
        compose.runOnIdle { f.state = f.state.copy(formError = true, formAvailable = false) }
        compose.onNodeWithText("Réessayer").assertIsDisplayed()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            f.state = f.state.copy(formError = false, formAvailable = true)
        }
        compose.onNodeWithText("Activer la signature").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            assertEquals(1, f.adapter.returnGeneration)
            assertFalse(f.adapter.consumeReturn())
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun `event detail reloads the form and restores body focus without replaying a send`() {
        f.transition(f.state.copy(submitError = EditorErrors.invalidForm))
        content()
        compose.onNodeWithContentDescription("Réponse illisible, Afficher les détails").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        val detail = ApplicationProvider.getApplicationContext<Context>().getString(UiR.string.editor_error_unknown)
        compose.onNodeWithText(detail).assertIsDisplayed()
        compose.runOnIdle { assertEquals(f.events.visibleEvent, f.adapter.detail) }
        compose.onNodeWithText("Recharger").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertEquals(listOf("reload"), f.calls)
            assertNull(f.adapter.detail)
            assertNull(f.events.visibleEvent)
            assertEquals(0, f.adapter.returnGeneration)
            assertFalse(f.adapter.consumeReturn())
        }
    }

    @Test fun `automatic entry waits for a user gesture instead of acknowledging information`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice"))
        f.transition(f.state.copy(submitError = EditorErrors.draftSaveFailed))
        val information = requireNotNull(f.events.visibleEvent)
        content(autoOpen = true)
        compose.onNodeWithText("Membres de la discussion").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(information, f.events.visibleEvent)
            assertFalse(f.memory.recipientEntryHandled)
            f.adapter.gesture(EditorBarGesture.ToggleFormat) // This actual gesture consumes the information.
            assertNull(f.events.visibleEvent)
            assertEquals(EditorBarMode.Normal, f.adapter.scaffoldChrome().bar.mode)
        }
        compose.onNodeWithText("Membres de la discussion").assertExists()
        compose.runOnIdle {
            assertTrue(f.memory.recipientEntryHandled)
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun `a refetch without newdest removes the open manager and its Back level`() {
        f.state = f.state.copy(canManageRecipients = true, recipients = listOf("alice"))
        content(autoOpen = true)
        compose.onNodeWithText("Membres de la discussion").assertExists()
        compose.runOnIdle { f.state = f.state.copy(isLoadingForm = true) }
        compose.onNodeWithText("Membres de la discussion").assertExists()
        compose.runOnIdle {
            f.state = f.state.copy(isLoadingForm = false, canManageRecipients = false, recipients = emptyList())
        }
        compose.onNodeWithText("Membres de la discussion").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle {
            assertNull(f.adapter.surface)
            assertEquals(1, f.adapter.returnGeneration)
            assertFalse(f.adapter.consumeReturn())
            f.adapter.back()
            assertEquals(listOf("close"), f.calls)
        }
    }

    @OptIn(DelicateCoilApi::class)
    @Test fun `private preview passes disabled disk reads and writes to the real Coil request`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val url = "https://images.example.org/private-reply.png"
        val policy = AtomicReference<CachePolicy?>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.data == url) policy.set(chain.request.diskCachePolicy)
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(0, width = 80, height = 60)).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(recorder); add(engine) }.build())
        try {
            f.state = f.state.copy(isPreviewVisible = true, preview = PostContent(listOf(PostBlock.Image(url, null))))
            content()
            compose.waitUntil(timeoutMillis = 5_000) { policy.get() != null }
            assertEquals(CachePolicy.DISABLED, policy.get())
        } finally {
            SingletonImageLoader.reset()
        }
    }

    private fun content(autoOpen: Boolean = false) {
        compose.setContent {
            RedfaceTheme {
                PrivateMessageReplyTestContent(f.state, f.body, f.actions, f.events, f.memory, autoOpen)
            }
        }
    }
}
