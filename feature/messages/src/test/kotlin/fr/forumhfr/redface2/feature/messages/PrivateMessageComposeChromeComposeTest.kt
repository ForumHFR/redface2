package fr.forumhfr.redface2.feature.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
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
import fr.forumhfr.redface2.core.ui.editor.EditorErrors
import fr.forumhfr.redface2.core.ui.editor.EditorRecipientState
import fr.forumhfr.redface2.core.ui.editor.EditorScaffold
import fr.forumhfr.redface2.core.ui.editor.EditorWindowMetrics
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerController
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w800dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivateMessageComposeChromeComposeTest {
    @get:Rule val compose = createComposeRule()
    private val f = PrivateMessageComposeChromeFixture()
    private val focus = FocusRequester()
    private var hides = 0
    private var shows = 0
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() { shows++ }
        override fun hide() { hides++ }
    }
    @Composable private fun Content() {
        val scope = rememberCoroutineScope()
        val smileys = remember { SmileyPickerController(scope, searchWiki = { _, _ -> emptyList() }) }
        f.openSmileys = { smileys.open() }
        val a = f.adapter
        CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
            RedfaceTheme {
                EditorScaffold(f.body, EditorWindowMetrics(740.dp, 0.dp, 0.dp), a.title(), a.context(), a.chrome(),
                    a.barActions, a.submitState, a.submitActions, a.imageActions, a::closeRequested,
                    bodyFocusRequester = focus, surfaceFocus = a.focusMemory, onSystemBack = { a.back() },
                    onOpenHeader = a::openHeader, headerLabel = stringResource(R.string.messages_compose_header_open),
                    fieldArea = { body -> PrivateMessageComposeChromePreview(a, f.body, focus, body) },
                    overlay = { restore, body ->
                        PrivateMessageComposeChromeSurfaces(a, f.body, f.recipients, f.subject, smileys, restore, body)
                    })
            }
        }
    }
    private fun prefill() {
        f.state = f.state.copy(recipientState = EditorRecipientState(listOf("alice")), subject = "Sujet privé")
    }
    @Test fun `ED113 empty entry opens chips and Next preserves buffer then returns selection and closed IME`() {
        compose.setContent { Content() }
        val recipients = compose.onNode(hasSetTextAction() and hasText("Destinataires"))
        recipients.assertIsFocused().performTextInput("sans virgule")
        recipients.performImeAction()
        compose.onNode(hasSetTextAction() and hasText("Sujet")).assertIsFocused()
        compose.runOnIdle { f.adapter.focusMemory.imeBeforeSurface = false; hides = 0; shows = 0 }
        compose.onNodeWithText("Retour au message").performClick()
        compose.onNodeWithTag("editor-scaffold-field").assertIsFocused()
        compose.runOnIdle {
            assertEquals("sans virgule", f.state.recipientState.buffer)
            assertEquals(TextRange(6, 1), f.body.selection)
            assertTrue(hides > 0)
            assertEquals(0, shows)
            assertFalse(f.adapter.consumeReturn())
        }
    }
    @Test fun `ED113 prefilled summary has no recipients status and completion focuses the cause field`() {
        prefill()
        compose.setContent { Content() }
        compose.onNodeWithText("Nouveau message").assertExists()
        compose.onNodeWithText("1 destinataire · Sujet privé").assertExists()
        compose.runOnIdle { assertNull(f.adapter.scaffoldChrome().secondStatus); assertNull(f.adapter.surface) }
        listOf(EditorErrors.unknownRecipient to "Destinataires", EditorErrors.missingSubject to "Sujet")
            .forEach { (error, label) ->
                compose.runOnIdle {
                    f.transition(f.state.copy(submitError = error))
                }
                val summary = error.summary.resolve(RuntimeEnvironment.getApplication())
                compose.onNodeWithContentDescription("$summary, Compléter").performClick()
                compose.onNode(hasSetTextAction() and hasText(label)).assertIsFocused()
                compose.runOnIdle { f.adapter.focusMemory.imeBeforeSurface = true; hides = 0 }
                compose.onNodeWithText("Retour au message").performClick()
                compose.onNodeWithTag("editor-scaffold-field").assertIsFocused()
                compose.runOnIdle { assertEquals(0, hides); assertEquals(TextRange(6, 1), f.body.selection) }
            }
    }
    @Test fun `ED40 restore discard and reload detail all render and reach their existing business ports`() {
        prefill()
        f.state = f.state.copy(restorableDraft = "brouillon", restorableSubject = "titre",
            restorableRecipients = "bob")
        compose.setContent { Content() }
        compose.runOnIdle { f.adapter.scaffoldChrome().firstStatus!!.onClick() }
        compose.onNodeWithText("Restaurer").performClick()
        compose.runOnIdle { f.adapter.scaffoldChrome().firstStatus!!.onClick() }
        compose.onNodeWithText("Ignorer").performClick()
        compose.runOnIdle {
            f.transition(f.state.copy(submitError = EditorErrors.invalidForm))
        }
        val summary = EditorErrors.invalidForm.summary.resolve(RuntimeEnvironment.getApplication())
        compose.onNodeWithContentDescription("$summary, Afficher les détails").performClick()
        compose.onNodeWithText("Recharger").performClick()
        compose.runOnIdle {
            assertEquals(listOf("restore", "discard", "reload"), f.calls)
            assertNull(f.adapter.surface)
            assertNull(f.adapter.detail)
        }
    }
    @Test fun `ED71 options have only three toggles and header inputs freeze during sending`() {
        prefill()
        compose.setContent { Content() }
        compose.onNodeWithContentDescription("Options").performClick()
        compose.onAllNodes(isToggleable()).assertCountEquals(3)
        compose.runOnIdle { f.adapter.closeSurface(); f.adapter.openHeader() }
        compose.runOnIdle { f.state = f.state.copy(isSubmitting = true) }
        compose.onNodeWithText("Destinataires").assertIsNotEnabled()
        compose.onNodeWithText("Sujet").assertIsNotEnabled()
    }
    @Test fun `ED70 smiley surface renders the common sheet`() {
        prefill()
        compose.setContent { Content() }
        compose.runOnIdle { f.adapter.barActions.onSmileys() }
        compose.onNodeWithTag("smiley_picker_sheet").assertExists()
    }
    @OptIn(DelicateCoilApi::class)
    @Test fun `ED60 preview image requests disable disk reads and writes`() {
        val url = "https://example.com/private-preview.png"
        val observed = CopyOnWriteArrayList<CachePolicy>()
        val recorder = object : Interceptor {
            override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                if (chain.request.data == url) observed += chain.request.diskCachePolicy
                return chain.proceed()
            }
        }
        val engine = FakeImageLoaderEngine.Builder().intercept(url, ColorImage(width = 320, height = 240)).build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(RuntimeEnvironment.getApplication())
            .components { add(recorder); add(engine) }.build())
        try {
            prefill()
            f.state = f.state.copy(isPreviewVisible = true, preview = PostContent(listOf(PostBlock.Image(url, null))))
            compose.setContent { Content() }
            compose.waitUntil(timeoutMillis = 5_000) { observed.isNotEmpty() }
            assertTrue(observed.all { it == CachePolicy.DISABLED })
        } finally { SingletonImageLoader.reset() }
    }
}
