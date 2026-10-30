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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.domain.smiley.SmileyLocalCollectionSnapshot
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.model.LocalFavoriteSmiley
import fr.forumhfr.redface2.core.model.SmileyUsageEntry
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
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
import org.junit.Assert.assertSame
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
    private val diagnostics = DiagnosticsLog()
    private val guardEvents = mutableListOf<String>()
    private var lastSheetOffsetPx: Float? = null

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
            assertTrue(diagnostics.entries.value.any { it.message == "sheet_close cause=unknown" })
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
    fun `grid predictive back follows progress restores on cancellation and dismisses on commit`() {
        val controller = controller()
        mount(controller)
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        val tab = text(R.string.editor_smiley_tab_standard)
        val before = tab.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            dialog.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            dialog.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.7f))
        }
        val progressed = tab.fetchSemanticsNode().boundsInRoot
        assertTrue("M3 must scale the grid during predictive back", progressed.width < before.width)
        compose.runOnIdle { dialog.onBackPressedDispatcher.dispatchOnBackCancelled() }
        val cancelled = tab.fetchSemanticsNode().boundsInRoot
        assertEquals(before.width, cancelled.width, 0.5f)
        assertEquals(before.top, cancelled.top, 0.5f)
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertTrue(dialog.isShowing)
            assertTrue(controller.state.value is SmileyPickerState.Open)
            dialog.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            dialog.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(1f))
            dialog.onBackPressedDispatcher.onBackPressed()
        }
        tab.assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
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

    @Test
    fun `double favorite long press then downward movement before release keeps the detail open`() {
        val smiley = wiki.first()
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", favorites = listOf(LocalFavoriteSmiley(smiley.token, smiley.imageUrl, 1)),
        )
        val controller = SmileyPickerController(
            CoroutineScope(dispatcher), { _, _ -> wiki }, diagnostics = diagnostics,
            favoriteSources = SmileyPickerFavoriteSources(
                favoriteSmileys = { listOf(smiley) }, localCollections = locals,
                hfrFavoriteMutations = SmileyFavoriteMutations(
                    { HfrFavoriteSmileyMutationResult.Success(listOf(smiley)) },
                    { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
                ),
            ),
            wikiKeywords = SmileyWikiKeywordsSource(load = {
                SmileyWikiKeywordsResult.Loaded(
                    listOf("feu", "feux", "fire", "brule", "bruler", "burn", "chaud", "hot", "cramer", "cramé"),
                )
            }),
        )
        dispatcher.scheduler.runCurrent()
        controller.open()
        mount(controller)
        val target = compose.onAllNodesWithContentDescription(description(smiley))[0]
        // Inject on the SAME surviving node before/after the lazy cell disappears, and keep
        // the finger's physical root position. A new DOWN would test a different gesture.
        val sheet = compose.onNodeWithTag("smiley_picker_sheet")
        val gestures = sheetGestures()
        gestures.trace = { event -> guardEvents += event; println("SmileyGuard $event") }
        try {
            val sheetNode = sheet.fetchSemanticsNode()
            val pressInRoot = target.fetchSemanticsNode().boundsInRoot.center
            val initial = gestureCheckpoint("before_down root=$pressInRoot", controller, gestures)
            assertTrue("the guard must contain the cell's DOWN; $initial", sheetNode.boundsInRoot.contains(pressInRoot))
            sheet.performTouchInput {
                down(pressInRoot - sheetNode.positionInRoot)
                advanceEventTime(700)
            }
            val down = gestureCheckpoint("after_down", controller, gestures)
            assertTrue("the real guard must receive DOWN before opening detail; $down", gestures.pressed)
            compose.mainClock.advanceTimeBy(700)
            val opened = gestureCheckpoint("detail_open", controller, gestures)
            assertEquals(
                "long press must open the expected detail; $opened",
                smiley, (controller.state.value as? SmileyPickerState.Open)?.detailSmiley,
            )
            text(R.string.editor_smiley_detail_title).assertIsDisplayed()
            // Also exercise a result arriving while the original finger is still held down.
            compose.runOnIdle { dispatcher.scheduler.runCurrent() }
            val loaded = gestureCheckpoint("keywords_loaded", controller, gestures)
            assertSame("recomposition must retain the same guard; $loaded", gestures, sheetGestures())
            val detailTop = sheet.fetchSemanticsNode().positionInRoot.y
            // These injections keep the physical finger down while combinedClickable disappears.
            // positionInRoot belongs to content INSIDE draggableAnchors, unlike the old outer tag.
            sheet.performTouchInput { repeat(6) { moveBy(Offset(0f, 80f)) } }
            val moved = gestureCheckpoint("after_moves", controller, gestures)
            assertEquals("the held gesture must not move the sheet; $moved", 0, dismissals)
            assertEquals(
                "the held gesture must not change the visible offset; $moved",
                detailTop, sheet.fetchSemanticsNode().positionInRoot.y, 0.5f,
            )
            sheet.performTouchInput { up() }
            val released = gestureCheckpoint("after_up", controller, gestures)
            compose.runOnIdle {
                assertEquals("UP/settle must not dismiss the sheet; $released", 0, dismissals)
                assertEquals(
                    "UP/settle must retain the detail; $released",
                    smiley, (controller.state.value as? SmileyPickerState.Open)?.detailSmiley,
                )
                assertNull("the long press must not insert a smiley; $released", inserted)
            }
            text(R.string.editor_smiley_detail_title).assertIsDisplayed()
            compose.onNodeWithTag("smiley_detail_viewport").performTouchInput { swipeDown(durationMillis = 150) }
            val next = gestureCheckpoint("new_swipe", controller, gestures)
            compose.runOnIdle {
                assertEquals("a NEW gesture must still dismiss the sheet; $next", 1, dismissals)
                assertFalse("a NEW DOWN must release the latch; $next", gestures.blocked)
                assertTrue(
                    "M3's shared dismissal must record unknown; $next",
                    diagnostics.entries.value.any { it.message == "sheet_close cause=unknown" },
                )
            }
            text(R.string.editor_smiley_detail_title).assertDoesNotExist()
        } finally {
            gestures.trace = null
        }
    }

    @Test
    fun `same smiley in both favorite sections survives repeated grid save and restoration`() {
        val target = wiki.first()
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", favorites = listOf(LocalFavoriteSmiley(target.token, target.imageUrl, 1)),
        )
        val controller = SmileyPickerController(
            CoroutineScope(dispatcher), { _, _ -> wiki }, diagnostics = diagnostics,
            favoriteSources = SmileyPickerFavoriteSources(
                favoriteSmileys = { listOf(target) }, localCollections = locals,
                hfrFavoriteMutations = SmileyFavoriteMutations(
                    { HfrFavoriteSmileyMutationResult.Success(listOf(target)) },
                    { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
                ),
            ),
        )
        dispatcher.scheduler.runCurrent()
        controller.open()
        mount(controller)
        repeat(4) { index ->
            val cells = compose.onAllNodesWithContentDescription(description(target))
            cells.assertCountEquals(2)
            cells[index % 2].performTouchInput { longClick() }
            text(R.string.editor_smiley_detail_title).assertIsDisplayed()
            text(R.string.editor_smiley_detail_local_favorite).assertIsDisplayed()
            text(R.string.editor_smiley_detail_hfr_favorite).assertIsDisplayed()
            back()
        }
        compose.onAllNodesWithContentDescription(description(target))[0].performTouchInput { longClick() }
        text(R.string.editor_smiley_local_favorite_remove).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        text(R.string.editor_smiley_detail_local_favorite).assertDoesNotExist()
        text(R.string.editor_smiley_favorite_remove).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        text(R.string.editor_smiley_detail_hfr_favorite).assertDoesNotExist()
        text(R.string.editor_smiley_detail_title).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, dismissals)
            assertTrue(controller.state.value is SmileyPickerState.Open)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun `scrim tap closes detail and sheet without guessing the shared M3 cause`() {
        val controller = controller()
        mount(controller)
        openDetail(builtin)
        tapScrim()
        text(R.string.editor_smiley_detail_title).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(diagnostics.entries.value.any { it.message == "sheet_close cause=unknown" })
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h1200dp-xxhdpi")
    fun `scrim tap while a short drag settles never records a swipe cause`() {
        val controller = controller()
        mount(controller)
        val sheet = compose.onNodeWithTag("smiley_picker_sheet")
        val node = sheet.fetchSemanticsNode()
        val before = node.positionInRoot.y
        // Touch injection uses px: a fixed 48 px does not pin the distance beyond touch slop.
        // Exceed the actual slop, leaving only 16 dp of travel (below M3's positional threshold).
        val displacement = node.layoutInfo.viewConfiguration.touchSlop + 16f * node.layoutInfo.density.density
        compose.mainClock.autoAdvance = false
        try {
            sheet.performTouchInput {
                down(Offset(centerX, 24f))
                // Small displacement and slow release: neither distance nor velocity can dismiss.
                moveBy(Offset(0f, displacement), delayMillis = 600)
            }
            compose.mainClock.advanceTimeByFrame()
            val dragged = sheet.fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "drag must exceed touch slop: before=$before dragged=$dragged displacement=$displacement",
                dragged > before,
            )
            sheet.performTouchInput {
                advanceEventTime(200)
                up()
            }
            compose.mainClock.advanceTimeByFrame()
            val settling = sheet.fetchSemanticsNode().positionInRoot.y
            assertTrue(
                "tap must precede the end of settle: before=$before dragged=$dragged settling=$settling",
                settling > before,
            )
            tapScrim()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        text(R.string.editor_smiley_tab_standard).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(diagnostics.entries.value.any { it.message == "sheet_close cause=unknown" })
            assertFalse(diagnostics.entries.value.any { it.message == "sheet_close cause=swipe" })
        }
    }

    @Test
    fun `handle click has its own cause instead of being reported as an outside tap`() {
        val controller = controller()
        mount(controller)
        val closeLabel = context.getString(R.string.editor_smiley_picker_close)
        compose.onNode(SemanticsMatcher("handle close action") {
            it.config.contains(SemanticsActions.OnClick) && it.config[SemanticsActions.OnClick].label == closeLabel
        }).performClick()
        compose.runOnIdle {
            assertEquals(1, dismissals)
            assertTrue(diagnostics.entries.value.any { it.message == "sheet_close cause=sheet_action" })
            assertFalse(diagnostics.entries.value.any { it.message == "sheet_close cause=outside_tap" })
        }
    }

    @Test
    fun `sheet transitions and debounced height are logged without tokens or recomposition spam`() {
        val controller = controller()
        mount(controller)
        compose.waitUntil(5_000) { diagnostics.entries.value.any { it.message.startsWith("sheet_height ") } }
        compose.runOnIdle {
            val messages = diagnostics.entries.value.map { it.message }
            assertEquals(1, messages.count { it == "sheet_open" })
            assertEquals(1, messages.count { it == "ui_attached" })
            assertTrue(messages.any { it == "sheet_state current=expanded target=expanded" })
        }
        val stableEvents = diagnostics.entries.value.count {
            it.message.startsWith("sheet_height ") || it.message.startsWith("sheet_state ")
        }
        repeat(3) { index -> compose.runOnIdle { controller.onQueryChanged("x$index") } }
        compose.runOnIdle {
            assertEquals(stableEvents, diagnostics.entries.value.count {
                it.message.startsWith("sheet_height ") || it.message.startsWith("sheet_state ")
            })
        }
        openDetail(builtin)
        back()
        compose.runOnIdle { controller.dismiss() }
        compose.runOnIdle {
            val entries = diagnostics.entries.value
            assertTrue(entries.all { it.tag == "SmileyPicker" })
            assertEquals(1, entries.count { it.message == "detail_open kind=standard" })
            assertEquals(1, entries.count { it.message == "detail_close cause=back" })
            assertEquals(1, entries.count { it.message == "sheet_close cause=controller" })
            assertEquals(1, entries.count { it.message == "ui_detached" })
            assertFalse(entries.any { it.message.contains(builtin.token) || it.message.contains("https://") })
        }
    }

    private fun controller() = SmileyPickerController(
        CoroutineScope(dispatcher), { _, _ -> wiki },
        diagnostics = diagnostics,
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

    private fun tapScrim() {
        val dialog = compose.onNode(isDialog())
        val dialogNode = dialog.fetchSemanticsNode()
        val dialogBounds = dialogNode.boundsInRoot
        val sheetBounds = compose.onNodeWithTag("smiley_picker_sheet").fetchSemanticsNode().boundsInRoot
        val exposedHeight = sheetBounds.top - dialogBounds.top
        assertTrue("no exposed scrim: dialog=$dialogBounds visibleSheet=$sheetBounds", exposedHeight > 0f)
        val tapInRoot = Offset(dialogBounds.center.x, dialogBounds.top + exposedHeight / 2f)
        assertFalse("scrim tap $tapInRoot must be outside visibleSheet=$sheetBounds", sheetBounds.contains(tapInRoot))
        dialog.performTouchInput { click(tapInRoot - dialogNode.positionInRoot) }
    }

    private fun sheetGestures(): SmileySheetGestures =
        compose.onNodeWithTag("smiley_picker_sheet").fetchSemanticsNode().layoutInfo.getModifierInfo()
            .mapNotNull { (it.modifier as? SmileySheetGesturesElement)?.gestures }.single()

    private fun gestureCheckpoint(
        step: String,
        controller: SmileyPickerController,
        gestures: SmileySheetGestures,
    ): String {
        val sheet = compose.onAllNodesWithTag("smiley_picker_sheet").fetchSemanticsNodes().singleOrNull()
        val layout = compose.onAllNodesWithTag("smiley_picker_sheet_layout").fetchSemanticsNodes().singleOrNull()
        val offset = sheet?.positionInRoot?.y
        if (offset != null) lastSheetOffsetPx = offset
        val state = controller.state.value
        val stateLabel = if (state is SmileyPickerState.Open) "Open(detail=${state.detailSmiley != null})" else "Hidden"
        val messages = diagnostics.entries.value.joinToString(" | ") { it.message }
        val status = "step=$step state=$stateLabel pressed=${gestures.pressed} blocked=${gestures.blocked} " +
            "dismissals=$dismissals contentOffsetPx=$offset lastVisibleOffsetPx=$lastSheetOffsetPx " +
            "visibleBounds=${sheet?.boundsInRoot} outerLayoutBounds=${layout?.boundsInRoot} diagnostics=[$messages]"
        println("SmileyGuard $status")
        return "$status guardEvents=[${guardEvents.joinToString("\n")}]"
    }

    private fun back() = compose.onNodeWithContentDescription(context.getString(R.string.editor_smiley_detail_back))
        .performClick()

    private fun backEvent(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)

    private fun text(resource: Int) = compose.onNodeWithText(context.getString(resource))

    private fun description(smiley: EditorSmiley) =
        context.getString(R.string.editor_smiley_insert_description, smiley.token)
}
