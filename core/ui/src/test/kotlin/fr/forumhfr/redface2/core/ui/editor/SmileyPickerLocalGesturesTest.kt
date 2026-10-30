package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyPickerLocalGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val locals = FakePickerLocalCollections()
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private val wiki = EditorSmiley("[:fictif a]", "https://example.invalid/a.gif", EditorSmileySource.WIKI)
    private var inserted: String? = null

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun images() {
        locals.snapshot.value = SmileyLocalCollectionSnapshot("alice")
        val engine = FakeImageLoaderEngine.Builder().apply {
            (BUILTIN_HFR_SMILEYS + wiki).forEach {
                intercept(it.imageUrl, ColorImage(0xFF808080.toInt(), width = 70, height = 50))
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `three tabs show ordered sections and Standard excludes all favorite sections`() {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", listOf(LocalFavoriteSmiley(wiki.token, wiki.imageUrl, 1)),
            listOf(SmileyUsageEntry(wiki.token, 2, 1)),
        )
        mount(controller(hfr = listOf(wiki.copy(source = EditorSmileySource.HFR_FAVORITE))))
        tab(R.string.editor_smiley_tab_favorites).assertIsSelected()
        val titles = listOf(R.string.editor_smiley_section_local, R.string.editor_smiley_section_favorites,
            R.string.editor_smiley_section_top)
        val positions = titles.map { tab(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals(positions.sorted(), positions)
        tab(R.string.editor_smiley_tab_standard).performClick()
        titles.forEach { tab(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription(description(builtin)).assertIsDisplayed()
        tab(R.string.editor_smiley_tab_wiki).assertExists()
    }

    @Test
    fun `empty Favorites explains local favorites and usage with no section headings`() {
        mount(controller())
        tab(R.string.editor_smiley_tab_standard).assertIsSelected()
        tab(R.string.editor_smiley_tab_favorites).performClick()
        tab(R.string.editor_smiley_favorites_hint).assertIsDisplayed()
        listOf(R.string.editor_smiley_section_local, R.string.editor_smiley_section_favorites,
            R.string.editor_smiley_section_top).forEach { tab(it).assertDoesNotExist() }
    }

    @Test
    fun `late collections never retake the tab selected by the user`() {
        mount(controller())
        tab(R.string.editor_smiley_tab_wiki).performClick()
        compose.runOnIdle {
            locals.snapshot.value = SmileyLocalCollectionSnapshot(
                "alice", listOf(LocalFavoriteSmiley(wiki.token, wiki.imageUrl, 1)),
            )
            dispatcher.scheduler.runCurrent()
        }
        tab(R.string.editor_smiley_tab_wiki).assertIsSelected()
        tab(R.string.editor_smiley_section_local).assertDoesNotExist()
        tab(R.string.editor_smiley_tab_favorites).performClick()
        tab(R.string.editor_smiley_section_local).assertIsDisplayed()
    }

    @Test
    fun `builtin detail has local action and matching TalkBack action while insertion is unchanged`() {
        val controller = controller()
        mount(controller)
        assertActions(builtin, listOf(R.string.editor_smiley_local_favorite_add))
        compose.onNodeWithContentDescription(description(builtin)).performTouchInput { longClick() }
        tab(R.string.editor_smiley_local_favorite_add).performScrollTo().performClick()
        compose.runOnIdle {
            dispatcher.scheduler.runCurrent()
            assertNull(inserted)
            assertEquals(listOf(Triple("alice", builtin.token, builtin.imageUrl)), locals.calls)
        }
        tab(R.string.editor_smiley_local_favorite_added).assertIsDisplayed()
        tab(R.string.editor_smiley_detail_insert).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(builtin.token, inserted) }
    }

    @Test
    fun `Wiki perso detail lists both applicable actions and TalkBack can invoke local addition`() {
        locals.snapshot.value = SmileyLocalCollectionSnapshot(
            "alice", listOf(LocalFavoriteSmiley(wiki.token, wiki.imageUrl, 1)),
        )
        val controller = controller()
        controller.onQueryChanged("fictif")
        dispatcher.scheduler.advanceUntilIdle()
        controller.dismiss()
        controller.open()
        mount(controller)
        val labels = listOf(R.string.editor_smiley_local_favorite_remove, R.string.editor_smiley_favorite_add)
        assertActions(wiki, labels)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        labels.forEach { tab(it).performScrollTo().assertIsDisplayed() }
        tab(R.string.editor_smiley_local_favorite_remove).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithContentDescription(context.getString(R.string.editor_smiley_detail_back)).performClick()
        assertActions(wiki, listOf(R.string.editor_smiley_local_favorite_add, R.string.editor_smiley_favorite_add))
        val action = compose.onNodeWithContentDescription(description(wiki)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single { it.label == context.getString(R.string.editor_smiley_local_favorite_add) }.action
        compose.runOnIdle {
            action()
            dispatcher.scheduler.runCurrent()
            assertEquals(
                listOf(Triple("alice", wiki.token, null), Triple("alice", wiki.token, wiki.imageUrl)), locals.calls,
            )
        }
    }

    @Test
    fun `feedback is persistent in Favorites and expires in both other tabs without moving content`() {
        val controller = controller()
        mount(controller)
        compose.mainClock.autoAdvance = false
        val cell = compose.onNodeWithContentDescription(description(builtin))
        val bounds = cell.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            (controller.state.value as SmileyPickerState.Open).onLocalFavoriteChange?.invoke(builtin)
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        tab(R.string.editor_smiley_local_favorite_added).assertIsDisplayed()
        assertEquals(bounds, cell.fetchSemanticsNode().boundsInRoot)
        tab(R.string.editor_smiley_tab_wiki).performClick()
        compose.mainClock.advanceTimeByFrame()
        tab(R.string.editor_smiley_local_favorite_added).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        compose.mainClock.advanceTimeByFrame()
        tab(R.string.editor_smiley_local_favorite_added).assertDoesNotExist()
        tab(R.string.editor_smiley_tab_standard).performClick()
        compose.mainClock.advanceTimeByFrame()
        tab(R.string.editor_smiley_local_favorite_added).assertDoesNotExist()
        tab(R.string.editor_smiley_tab_favorites).performClick()
        compose.mainClock.advanceTimeByFrame()
        tab(R.string.editor_smiley_local_favorite_added).assertIsDisplayed()
    }

    @Test
    fun `saved tab preserves a user selection after leaving a restored Wiki query`() {
        val controller = controller()
        controller.onQueryChanged("fictif")
        controller.dismiss()
        controller.open()
        // StateRestorationTester replaces the host registry, not ModalBottomSheet's dialog registry.
        // Exercise the production saveable state in the reachable registry instead of claiming a rotation.
        lateinit var selection: MutableState<Int>
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            val state by controller.state.collectAsState()
            selection = rememberSmileyPickerTab((state as SmileyPickerState.Open).initialTab)
        }
        compose.runOnIdle {
            assertEquals(SmileyPickerTab.WIKI.ordinal, selection.value)
            selection.value = SmileyPickerTab.STANDARD.ordinal
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(SmileyPickerTab.STANDARD.ordinal, selection.value) }
    }

    @Test
    fun `restored Wiki opening ignores a saved Standard slot`() {
        // Deliberately not Compose state: change the decision only when the saved tree is recreated,
        // leaving a Standard value in the old registry to challenge the restored Wiki opening (#824).
        var initialTab = SmileyPickerTab.STANDARD
        lateinit var selection: MutableState<Int>
        val restoration = StateRestorationTester(compose)
        restoration.setContent { selection = rememberSmileyPickerTab(initialTab) }
        compose.runOnIdle {
            assertEquals(SmileyPickerTab.STANDARD.ordinal, selection.value)
            initialTab = SmileyPickerTab.WIKI
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertEquals(SmileyPickerTab.WIKI.ordinal, selection.value) }
    }

    @Test
    fun `user selection after leaving restored Wiki survives collection updates during the opening`() {
        val controller = controller()
        controller.onQueryChanged("fictif")
        controller.dismiss()
        controller.open()
        mount(controller)
        tab(R.string.editor_smiley_tab_wiki).assertIsSelected()
        tab(R.string.editor_smiley_tab_standard).performClick()
        compose.runOnIdle {
            locals.snapshot.value = SmileyLocalCollectionSnapshot(
                "alice", listOf(LocalFavoriteSmiley(wiki.token, wiki.imageUrl, 1)),
            )
            dispatcher.scheduler.runCurrent()
            controller.open()
            controller.refreshFavorites()
        }
        tab(R.string.editor_smiley_tab_standard).assertIsSelected()
    }

    @Test
    fun `restored Wiki state delivered after first composition replaces the initial Standard slot`() {
        var state by mutableStateOf(SmileyPickerState.Open())
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerSheet(state, {}, {}, {})
            }
        }
        tab(R.string.editor_smiley_tab_standard).assertIsSelected()
        compose.runOnIdle { state = SmileyPickerState.Open(query = "fictif") }
        tab(R.string.editor_smiley_tab_wiki).assertIsSelected()
    }

    private fun controller(hfr: List<EditorSmiley> = emptyList()) = SmileyPickerController(
        CoroutineScope(dispatcher), { _, _ -> listOf(wiki) },
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = { hfr },
            hfrFavoriteMutations = SmileyFavoriteMutations(
                { HfrFavoriteSmileyMutationResult.Success(listOf(wiki)) },
                { HfrFavoriteSmileyMutationResult.Success(emptyList()) },
            ),
            localCollections = locals,
        ),
    ).also { dispatcher.scheduler.runCurrent(); it.open() }

    private fun mount(controller: SmileyPickerController) = compose.setContent { Sheet(controller) }

    @androidx.compose.runtime.Composable
    private fun Sheet(controller: SmileyPickerController) {
        RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
            val state by controller.state.collectAsState()
            (state as? SmileyPickerState.Open)?.let { open ->
                SmileyPickerSheet(open, controller::dismiss, controller::onQueryChanged, {
                    inserted = it
                    controller.dismiss()
                })
            }
        }
    }

    private fun assertActions(smiley: EditorSmiley, resources: List<Int>) {
        val node = compose.onNodeWithContentDescription(description(smiley))
        val config = node.fetchSemanticsNode().config
        assertEquals(
            context.getString(R.string.editor_smiley_detail_show), config[SemanticsActions.OnLongClick].label,
        )
        assertEquals((listOf(R.string.editor_smiley_detail_show) + resources).map { context.getString(it) },
            config[SemanticsActions.CustomActions].map { it.label })
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description(smiley))))
    }

    private fun tab(resource: Int) = compose.onNodeWithText(context.getString(resource))

    private fun description(smiley: EditorSmiley) =
        context.getString(R.string.editor_smiley_insert_description, smiley.token)
}
