package fr.forumhfr.redface2.core.ui.editor

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowDialog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerDetailGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val locals = FakePickerLocalCollections()
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private val wiki = (0..99).map {
        EditorSmiley("[:fictif $it:2]", "https://example.invalid/$it.gif", EditorSmileySource.WIKI)
    }
    private var inserted: String? = null
    private var dismissals = 0

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun images() {
        val engine = FakeImageLoaderEngine.Builder().apply {
            (BUILTIN_HFR_SMILEYS + wiki).forEach {
                intercept(it.imageUrl, ColorImage(0xFF808080.toInt(), width = 70, height = 50))
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `builtin without any favorite callback opens detail and inserts the exact token`() {
        val controller = SmileyPickerController(CoroutineScope(dispatcher), { _, _ -> emptyList() })
        controller.open()
        mount(controller)
        openDetail(builtin)
        text(R.string.editor_smiley_detail_title).assertIsDisplayed().assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        )
        compose.onNodeWithText(builtin.token).assertIsDisplayed()
        text(R.string.editor_smiley_detail_unused).assertIsDisplayed()
        text(R.string.editor_smiley_favorite_add).assertDoesNotExist()
        text(R.string.editor_smiley_favorite_remove).assertDoesNotExist()
        text(R.string.editor_smiley_detail_insert).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(builtin.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
    }

    @Test
    fun `TalkBack detail action is available on a builtin without favorite actions`() {
        val controller = SmileyPickerController(CoroutineScope(dispatcher), { _, _ -> emptyList() })
        controller.open()
        mount(controller)
        val action = compose.onNodeWithContentDescription(description(builtin)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].single()
        assertEquals(context.getString(R.string.editor_smiley_detail_show), action.label)
        compose.runOnIdle { action.action() }
        text(R.string.editor_smiley_detail_title).assertIsDisplayed()
    }

    @Test
    fun `grid without state or detail command only exposes insertion`() {
        var favorites by mutableStateOf<SmileyPickerState.Open?>(null)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerGrid(listOf(builtin), { inserted = it }, favorites = favorites)
            }
        }
        listOf(null, SmileyPickerState.Open()).forEach { state ->
            compose.runOnIdle { favorites = state; inserted = null }
            val cell = compose.onNodeWithContentDescription(description(builtin))
            val semantics = cell.fetchSemanticsNode().config
            assertFalse(semantics.contains(SemanticsActions.OnLongClick))
            assertFalse(semantics.contains(SemanticsActions.CustomActions))
            cell.performClick()
            compose.runOnIdle { assertEquals(builtin.token, inserted) }
        }
    }

    @Test
    fun `grid without detail command keeps its available favorite accessibility action`() {
        var favorite: EditorSmiley? = null
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerGrid(
                    listOf(builtin), { inserted = it },
                    favorites = SmileyPickerState.Open(onLocalFavoriteChange = { favorite = it }),
                )
            }
        }
        val cell = compose.onNodeWithContentDescription(description(builtin))
        val semantics = cell.fetchSemanticsNode().config
        assertFalse(semantics.contains(SemanticsActions.OnLongClick))
        val action = semantics[SemanticsActions.CustomActions].single()
        assertEquals(context.getString(R.string.editor_smiley_local_favorite_add), action.label)
        compose.runOnIdle {
            assertTrue(action.action())
            assertEquals(builtin, favorite)
            assertNull(inserted)
        }
        cell.performClick()
        compose.runOnIdle { assertEquals(builtin.token, inserted) }
    }

    @Test
    fun `system back returns to grid then dismisses the sheet and reopening has no detail`() {
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        text(R.string.editor_smiley_detail_title).assertDoesNotExist()
        text(R.string.editor_smiley_tab_standard).assertIsSelected()
        compose.runOnIdle {
            assertTrue(dialog.isShowing)
            assertNull((controller.state.value as SmileyPickerState.Open).detailSmiley)
            assertEquals(0, dismissals)
        }

        compose.runOnIdle { dialog.onBackPressedDispatcher.onBackPressed() }
        text(R.string.editor_smiley_tab_standard).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            assertFalse(dialog.isShowing)
            controller.open()
        }
        text(R.string.editor_smiley_tab_standard).assertIsSelected()
        text(R.string.editor_smiley_detail_title).assertDoesNotExist()
        compose.runOnIdle { assertNull((controller.state.value as SmileyPickerState.Open).detailSmiley) }
    }

    @Test
    fun `predictive back cancellation keeps detail and completion returns to grid in the same dialog`() {
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        val titleBounds = text(R.string.editor_smiley_detail_title).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            dialog.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            dialog.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.7f))
            dialog.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        text(R.string.editor_smiley_detail_title).assertIsDisplayed()
        assertEquals(titleBounds, text(R.string.editor_smiley_detail_title).fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(builtin, (controller.state.value as SmileyPickerState.Open).detailSmiley)
            dialog.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            dialog.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(1f))
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        text(R.string.editor_smiley_tab_standard).assertIsSelected()
        compose.runOnIdle { assertTrue(dialog.isShowing) }
    }

    @Test
    fun `arrow restores Wiki query scroll and tab without requesting search focus again`() {
        val controller = controller()
        controller.onQueryChanged("fictif")
        dispatcher.scheduler.advanceUntilIdle()
        controller.dismiss()
        controller.open()
        mount(controller)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(50)
        val cell = compose.onNodeWithContentDescription(description(wiki[50]))
        val bounds = cell.fetchSemanticsNode().boundsInRoot
        cell.performTouchInput { longClick() }
        text(R.string.editor_smiley_tab_wiki).assertDoesNotExist()
        // Keyboard hiding is checked below with a provider inside the dialog's own Compose owner.
        back()
        text(R.string.editor_smiley_tab_wiki).assertIsSelected()
        val search = compose.onNode(hasSetTextAction()).assertIsNotFocused()
        assertEquals("fictif", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(bounds, cell.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertNull((controller.state.value as SmileyPickerState.Open).detailSmiley)
            assertNull(inserted)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun `detail focus effect hides the dialog keyboard without reopening it on return`() {
        var detail by mutableStateOf<EditorSmiley?>(null)
        val autofocus = mutableStateOf(true)
        var keyboardHides = 0
        var keyboardShows = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShows++ }
            override fun hide() { keyboardHides++ }
        }
        var dialogKeyboard: SoftwareKeyboardController? = null
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                ModalBottomSheet(onDismissRequest = {}) {
                    dialogKeyboard = LocalSoftwareKeyboardController.current
                    // The dialog's Compose owner replaces the host provider. Inject INSIDE it
                    // to test the same focus effect that the real sheet runs in this scope.
                    CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                        SmileyDetailFocusEffect(detail, autofocus)
                    }
                }
            }
        }
        compose.runOnIdle {
            assertNotNull(dialogKeyboard)
            assertNotSame(keyboard, dialogKeyboard)
            assertEquals(0, keyboardHides)
            detail = builtin
        }
        compose.runOnIdle {
            assertTrue(keyboardHides > 0)
            assertFalse(autofocus.value)
            detail = null
        }
        compose.runOnIdle {
            assertEquals(1, keyboardHides)
            assertEquals(0, keyboardShows)
            assertFalse(autofocus.value)
        }
    }

    @Test
    fun `copy writes the exact code and keeps detail open`() {
        val controller = controller()
        controller.onQueryChanged("fictif")
        dispatcher.scheduler.advanceUntilIdle()
        controller.dismiss()
        controller.open()
        mount(controller)
        val target = wiki.first()
        openDetail(target)
        text(R.string.editor_smiley_detail_copy).performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(target.token, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            assertEquals(target, (controller.state.value as SmileyPickerState.Open).detailSmiley)
            assertNull(inserted)
        }
    }

    @Config(sdk = [32])
    @Test
    fun `copy on Android before 13 also shows the app feedback`() {
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        text(R.string.editor_smiley_detail_copy).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(context.getString(R.string.editor_smiley_detail_copied), ShadowToast.getTextOfLatestToast())
        }
    }

    @Test
    fun `detail shows a Long usage count and recalculates local chip and action after removal`() {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", favorites = listOf(LocalFavoriteSmiley(builtin.token, addedAt = 1)),
            usageCounts = mapOf(builtin.token to Long.MAX_VALUE),
        )
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        compose.onNodeWithText(context.resources.getQuantityString(
            R.plurals.editor_smiley_detail_usage, Int.MAX_VALUE, Long.MAX_VALUE,
        )).assertIsDisplayed()
        text(R.string.editor_smiley_detail_local_favorite).assertIsDisplayed()
        text(R.string.editor_smiley_local_favorite_remove).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        text(R.string.editor_smiley_local_favorite_removed).assertIsDisplayed()
        text(R.string.editor_smiley_detail_local_favorite).assertDoesNotExist()
        text(R.string.editor_smiley_local_favorite_add).performScrollTo().assertIsDisplayed()
        text(R.string.editor_smiley_detail_title).assertIsDisplayed()
    }

    @Test
    fun `Top long press opens detail independently of local favorite membership`() {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", top = listOf(SmileyUsageEntry(wiki[0].token, 3, 1)),
        )
        val controller = controller()
        mount(controller)
        text(R.string.editor_smiley_section_top).assertIsDisplayed()
        openDetail(wiki[0])
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.editor_smiley_detail_usage, 3, 3L))
            .assertIsDisplayed()
    }

    @Test
    fun `dismissal from detail removes the whole sheet and reopening starts on the grid`() {
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        // The dialog's onDismissRequest is the controller's dismiss, for scrim taps and swipes alike.
        compose.runOnIdle { controller.dismiss() }
        text(R.string.editor_smiley_detail_title).assertDoesNotExist()
        text(R.string.editor_smiley_tab_standard).assertDoesNotExist()
        compose.runOnIdle { controller.open() }
        text(R.string.editor_smiley_tab_standard).assertIsSelected()
        text(R.string.editor_smiley_detail_title).assertDoesNotExist()
    }

    private fun controller() = SmileyPickerController(
        CoroutineScope(dispatcher), { _, _ -> wiki },
        favoriteSources = SmileyPickerFavoriteSources(
            localCollections = locals,
            hfrFavoriteMutations = SmileyFavoriteMutations(
                { HfrFavoriteSmileyMutationResult.Success(listOf(wiki[0])) },
                { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
            ),
        ),
    ).also { dispatcher.scheduler.runCurrent(); it.open() }

    private fun mount(controller: SmileyPickerController) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val state by controller.state.collectAsState()
                (state as? SmileyPickerState.Open)?.let { open ->
                    SmileyPickerSheet(open, { dismissals++; controller.dismiss() }, controller::onQueryChanged, {
                        inserted = it
                        controller.dismiss()
                    })
                }
            }
        }
    }

    private fun openDetail(smiley: EditorSmiley) {
        compose.onNodeWithContentDescription(description(smiley)).performTouchInput { longClick() }
    }

    private fun back() = compose.onNodeWithContentDescription(context.getString(R.string.editor_smiley_detail_back))
        .performClick()

    private fun backEvent(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)

    private fun text(resource: Int) = compose.onNodeWithText(context.getString(resource))

    private fun description(smiley: EditorSmiley) =
        context.getString(R.string.editor_smiley_insert_description, smiley.token)
}
