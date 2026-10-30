package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.HfrFavoriteSmileyMutationResult
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
class SmileyPickerHfrGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()
    private val wiki = EditorSmiley("[:fictif_a:2]", "https://example.invalid/a.gif", EditorSmileySource.WIKI)
    private val other = EditorSmiley("[:fictif b]", "https://example.invalid/b.gif", EditorSmileySource.WIKI)
    private val favorite = wiki.copy(source = EditorSmileySource.HFR_FAVORITE)
    private val added = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private var inserted: String? = null

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun installFakeImageLoader() {
        val engine = FakeImageLoaderEngine.Builder().apply {
            (listOf(wiki, other) + BUILTIN_HFR_SMILEYS).forEach { smiley ->
                intercept(smiley.imageUrl, ColorImage(0xFF808080.toInt(), width = 70, height = 50))
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `wiki long press opens detail without inserting and its action adds`() {
        val controller = controller()
        showWiki(controller)
        mount(controller)
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_add)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }

        compose.runOnIdle {
            assertEquals(listOf(wiki.token), added)
            assertEquals(null, inserted)
            assertEquals(listOf(favorite), (controller.state.value as SmileyPickerState.Open).favoriteSmileys)
        }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_remove)).assertExists()
        backToGrid()
        // Wiki stays a search grid, even after an optimistic favorite has been added.
        compose.onNodeWithText(context.getString(R.string.editor_smiley_section_favorites)).assertDoesNotExist()
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_remove)
    }

    @Test
    fun `wiki TalkBack action removes an exact token already in favorites`() {
        val controller = controller(listOf(favorite))
        showWiki(controller)
        mount(controller)
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_remove)
        val cell = compose.onNodeWithContentDescription(description(wiki))
        val removeAction = cell.fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == context.getString(R.string.editor_smiley_favorite_remove) }.action
        compose.runOnIdle {
            assertTrue(removeAction())
            dispatcher.scheduler.runCurrent()
        }
        compose.runOnIdle {
            assertEquals(listOf(wiki.token), removed)
            assertEquals(null, inserted)
        }
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_add)
    }

    @Test
    fun `Favorites long press offers removal with the same accessibility label`() {
        val controller = controller(listOf(favorite))
        mount(controller)
        assertFavoriteActions(favorite, R.string.editor_smiley_favorite_remove)
        compose.onNodeWithContentDescription(description(favorite)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_remove))
            .performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.runOnIdle { assertEquals(listOf(favorite.token), removed) }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_removed)).assertExists()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_detail_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_detail_hfr_favorite)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).assertExists()
    }

    @Test
    fun `HFR failure stays visible in detail and restores its add action`() {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(add = { gate.await() })
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle {
            dispatcher.scheduler.runCurrent()
            gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
            dispatcher.scheduler.runCurrent()
        }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_detail_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_network_error)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).assertExists()
        compose.runOnIdle {
            assertEquals(wiki, (controller.state.value as SmileyPickerState.Open).detailSmiley)
            assertNull(inserted)
        }
    }

    @Test
    fun `feedback seen in detail is not replayed in Wiki or Standard and stays in Favorites`() {
        val controller = controller()
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()

        backToGrid()
        message.assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_standard)).performClick()
        message.assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_favorites)).performClick()
        message.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_wiki)).performClick()
        message.assertDoesNotExist()
    }

    @Test
    fun `HFR result received after leaving detail still shows an overlay in Standard and Wiki`() {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(add = { gate.await() })
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_pending))
            .performScrollTo().assertIsDisplayed()
        backToGrid()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_standard)).performClick()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertDoesNotExist()

        compose.runOnIdle {
            // Success keeps the pending gesture's generation: merely opening detail must not consume it.
            gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite)))
            dispatcher.scheduler.runCurrent()
        }
        message.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_wiki)).performClick()
        message.assertIsDisplayed()
    }

    @Test
    fun `opening another detail during grid feedback neither displays nor consumes that feedback`() {
        val controller = controller()
        showWiki(controller)
        mount(controller)
        compose.mainClock.autoAdvance = false
        val addAction = compose.onNodeWithContentDescription(description(wiki)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
            .single { it.label == context.getString(R.string.editor_smiley_favorite_add) }.action
        compose.runOnIdle {
            assertTrue(addAction())
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()

        listOf(
            R.string.editor_smiley_tab_wiki to other,
            R.string.editor_smiley_tab_standard to BUILTIN_HFR_SMILEYS.first(),
        ).forEach { (tab, smiley) ->
            compose.onNodeWithText(context.getString(tab)).performClick()
            compose.mainClock.advanceTimeByFrame()
            val detailAction = compose.onNodeWithContentDescription(description(smiley)).fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.editor_smiley_detail_show) }.action
            compose.runOnIdle { assertTrue(detailAction()) }
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithText(context.getString(R.string.editor_smiley_detail_title)).assertIsDisplayed()
            message.assertDoesNotExist()
            backToGrid()
            compose.mainClock.advanceTimeByFrame()
            message.assertIsDisplayed()
        }
    }

    @Test
    fun `another smiley keeps its disabled add or remove label without progress during an HFR mutation`() {
        val third = other.copy(token = "[:fictif c]")
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(
            favorites = listOf(other.copy(source = EditorSmileySource.HFR_FAVORITE)),
            wikiItems = listOf(wiki, other, third), add = { gate.await() },
        )
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_pending))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(1)
        backToGrid()

        listOf(
            other to R.string.editor_smiley_favorite_remove,
            third to R.string.editor_smiley_favorite_add,
        ).forEach { (smiley, label) ->
            compose.onNodeWithContentDescription(description(smiley)).performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(label)).performScrollTo().assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_pending)).assertDoesNotExist()
            compose.onAllNodes(progress, useUnmergedTree = true).assertCountEquals(0)
            backToGrid()
        }
        compose.runOnIdle {
            assertEquals(listOf(wiki.token), added)
            assertTrue(removed.isEmpty())
            gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(favorite, other)))
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `absent removal leaves account insertion and detail without HFR actions in either tab`() {
        val account = other.copy(source = EditorSmileySource.HFR_FAVORITE)
        val controller = controller(
            favorites = listOf(account, favorite),
            remove = { HfrFavoriteSmileyMutationResult.NotInFavorites(listOf(favorite)) },
        )
        mount(controller)
        assertFavoriteActions(account, R.string.editor_smiley_favorite_remove)
        compose.onNodeWithContentDescription(description(account)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_remove))
            .performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_remove)).assertDoesNotExist()
        val message = context.getString(R.string.editor_smiley_favorite_not_in_favorites)
        compose.onNodeWithText(message).assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        backToGrid()
        // Favorites keeps its persistent status even when detail already displayed this feedback.
        compose.onNodeWithText(message).assertIsDisplayed()
        assertNoFavoriteActions(account)
        assertFavoriteActions(favorite, R.string.editor_smiley_favorite_remove)

        compose.runOnIdle {
            // Keep this opening alive: dismiss() would consume the NotInFavorites feedback.
            controller.onQueryChanged("fictif")
            dispatcher.scheduler.advanceUntilIdle()
        }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_wiki)).performClick()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_section_favorites)).assertDoesNotExist()
        compose.onNodeWithText(message).assertDoesNotExist()
        assertNoFavoriteActions(other)
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_remove)
        compose.onNodeWithContentDescription(description(other)).performClick()
        compose.runOnIdle {
            assertEquals(other.token, inserted)
            assertEquals(listOf(other.token), removed)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            assertTrue(added.isEmpty())
        }
    }

    @Test
    fun `builtin without local collections keeps insertion without HFR favorite actions`() {
        val controller = controller()
        mount(controller)
        val builtin = BUILTIN_HFR_SMILEYS.first()
        assertNoFavoriteActions(builtin)
        compose.onNodeWithContentDescription(description(builtin)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(description(builtin))),
        )
        compose.onNodeWithContentDescription(description(builtin)).performClick()
        compose.runOnIdle {
            assertEquals(builtin.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            assertTrue(added.isEmpty())
            assertTrue(removed.isEmpty())
        }
    }

    @Test
    fun `wiki without callbacks keeps insertion`() {
        val controller = SmileyPickerController(
            scope = CoroutineScope(dispatcher),
            searchWiki = { _, _ -> listOf(wiki) },
        ).also { it.open() }
        showWiki(controller)
        mount(controller)
        assertNoFavoriteActions(wiki)
        compose.onNodeWithContentDescription(description(wiki)).performClick()
        compose.runOnIdle {
            assertEquals(wiki.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
    }

    @Test
    fun `wiki feedback never moves search or grid`() {
        var feedback by mutableStateOf<SmileyFavoriteFeedback?>(null)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerSheet(
                    state = SmileyPickerState.Open(
                        query = "fictif",
                        wiki = WikiSearchState.Results(listOf(wiki, other)),
                        favoriteFeedback = feedback,
                    ),
                    onDismiss = {}, onQueryChange = {}, onSmileyClicked = {},
                )
            }
        }
        val search = compose.onNode(hasSetTextAction())
        val cell = compose.onNodeWithContentDescription(description(wiki))
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val cellBounds = cell.fetchSemanticsNode().boundsInRoot
        val messages = listOf(
            SmileyFavoriteFeedback.Added to context.getString(R.string.editor_smiley_favorite_added),
            SmileyFavoriteFeedback.Full(7) to context.getString(R.string.editor_smiley_favorite_full, 7),
        )
        messages.forEach { (value, message) ->
            compose.runOnIdle { feedback = value }
            compose.onNodeWithText(message).assertIsDisplayed().assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
            assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
            assertEquals(cellBounds, cell.fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { feedback = null }
            compose.onNodeWithText(message).assertDoesNotExist()
            assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
            assertEquals(cellBounds, cell.fetchSemanticsNode().boundsInRoot)
        }
    }

    @Test
    fun `wiki feedback expires and the last row accepts touch insertion again`() {
        val controller = controller(wikiItems = listOf(other, other.copy(token = "[:fictif c]"), wiki))
        showWiki(controller)
        mount(controller)
        compose.mainClock.autoAdvance = false
        val search = compose.onNode(hasSetTextAction())
        // Three results fit in a single row; the third cell lies under the centered feedback.
        val cell = compose.onNodeWithContentDescription(description(wiki))
        val searchBounds = search.fetchSemanticsNode().boundsInRoot
        val cellBounds = cell.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            controller.onFavoriteClicked(wiki)
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()
        val coveredBounds = cellBounds.intersect(message.fetchSemanticsNode().boundsInRoot)
        assertTrue(coveredBounds.width > 0f && coveredBounds.height > 0f)
        val touchPosition = coveredBounds.center - cellBounds.topLeft
        compose.mainClock.advanceTimeBy(3500L)
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
        assertEquals(searchBounds, search.fetchSemanticsNode().boundsInRoot)
        assertEquals(cellBounds, cell.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            val open = controller.state.value as SmileyPickerState.Open
            assertEquals(SmileyFavoriteFeedback.Added, open.favoriteFeedback)
        }
        // A semantics click would bypass the Surface's touch interception and miss the regression.
        cell.performTouchInput { click(touchPosition) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(wiki.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
    }

    @Test
    fun `successive wiki additions redisplay identical feedback without an intermediate frame`() {
        val controller = controller(add = {
            val confirmed = listOf(wiki, other).filter { it.token in added }
                .map { it.copy(source = EditorSmileySource.HFR_FAVORITE) }
            HfrFavoriteSmileyMutationResult.Success(confirmed)
        })
        showWiki(controller)
        mount(controller)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            controller.onFavoriteClicked(wiki)
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
        compose.runOnIdle {
            controller.onFavoriteClicked(other)
            assertNull((controller.state.value as SmileyPickerState.Open).favoriteFeedback)
            // Complete before Compose can observe null: the gesture generation must reset visibility.
            dispatcher.scheduler.runCurrent()
            val open = controller.state.value as SmileyPickerState.Open
            assertEquals(SmileyFavoriteFeedback.Added, open.favoriteFeedback)
            assertEquals(listOf(wiki.token, other.token), added)
        }
        compose.mainClock.advanceTimeByFrame()
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3500L)
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
    }

    @Test
    fun `expired wiki feedback stays hidden across tab changes and a new gesture gets a fresh timeout`() {
        val controller = controller(add = {
            val confirmed = listOf(wiki, other).filter { it.token in added }
                .map { it.copy(source = EditorSmileySource.HFR_FAVORITE) }
            HfrFavoriteSmileyMutationResult.Success(confirmed)
        })
        showWiki(controller)
        mount(controller)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            controller.onFavoriteClicked(wiki)
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()

        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_favorites)).performClick()
        compose.mainClock.advanceTimeByFrame()
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        message.assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_wiki)).performClick()
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()

        compose.runOnIdle {
            controller.onFavoriteClicked(other)
            // Complete before Compose observes null; the new generation must also reset expiration.
            dispatcher.scheduler.runCurrent()
        }
        compose.mainClock.advanceTimeByFrame()
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3500L)
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(500L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
    }

    @Test
    fun `failure received while hidden displays a fresh wiki overlay on reopening`() {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(add = { gate.await() })
        showWiki(controller)
        mount(controller)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            controller.onFavoriteClicked(wiki)
            dispatcher.scheduler.runCurrent()
            controller.dismiss()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_tab_wiki)).assertDoesNotExist()
        compose.runOnIdle {
            gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
            dispatcher.scheduler.runCurrent()
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
        }
        // Time spent hidden must not consume the next opening's overlay timeout.
        compose.mainClock.advanceTimeBy(8000L)
        compose.runOnIdle { controller.open() }
        // The new dialog needs Android measure/layout between Compose frames to enter the screen.
        // Keep the clock paused and drive about 1 s of entry animation, within the 4 s timeout.
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_section_favorites)).assertDoesNotExist()
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_network_error))
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2500L)
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(1500L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
    }

    @Test
    fun `wiki feedback uses the accessibility recommended text timeout`() {
        val accessibilityManager = object : AccessibilityManager {
            override fun calculateRecommendedTimeoutMillis(
                originalTimeoutMillis: Long,
                containsIcons: Boolean,
                containsText: Boolean,
                containsControls: Boolean,
            ): Long {
                assertEquals(4000L, originalTimeoutMillis)
                assertEquals(false, containsIcons)
                assertEquals(true, containsText)
                assertEquals(false, containsControls)
                return 8000L
            }
        }
        var expired by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibilityManager) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    if (!expired) {
                        SmileyFavoriteOverlay(SmileyFavoriteFeedback.Added, onExpired = { expired = true })
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val message = compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_added))
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        message.assertIsDisplayed()
        compose.mainClock.advanceTimeBy(4000L)
        compose.mainClock.advanceTimeByFrame()
        message.assertDoesNotExist()
    }

    @Test
    fun `simple wiki click inserts the token and closes without a favorite mutation`() {
        val controller = controller()
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performClick()
        compose.runOnIdle {
            assertEquals(wiki.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            assertTrue(added.isEmpty())
            assertTrue(removed.isEmpty())
        }
    }

    @Test
    fun `in flight disables HFR gestures but permits insertion and retains a hidden failure`() {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(add = { gate.await() })
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performScrollTo().performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_pending))
            .performScrollTo().assertIsDisplayed()
        backToGrid()
        assertNoFavoriteActions(wiki)
        assertNoFavoriteActions(other)
        compose.onNodeWithContentDescription(description(wiki)).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                context.getString(R.string.editor_smiley_favorite_pending),
            ),
        )
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).assertDoesNotExist()
        compose.onNodeWithContentDescription(description(other)).performClick()
        compose.runOnIdle {
            assertEquals(other.token, inserted)
            gate.complete(HfrFavoriteSmileyMutationResult.TransportFailure)
            dispatcher.scheduler.runCurrent()
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            controller.open()
        }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_network_error)).assertExists()
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_add)
    }

    @Test
    fun `duplicate form entries and an optimistic addition have unique grid keys`() {
        val repeated = other.copy(source = EditorSmileySource.HFR_FAVORITE)
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(listOf(repeated, repeated), add = { gate.await() })
        mount(controller)
        compose.onAllNodesWithContentDescription(description(other)).assertCountEquals(1)
        compose.runOnIdle { controller.onFavoriteClicked(wiki) }
        compose.onAllNodesWithContentDescription(description(other)).assertCountEquals(1)
        compose.onAllNodesWithContentDescription(description(wiki)).assertCountEquals(1)
        compose.runOnIdle {
            gate.complete(HfrFavoriteSmileyMutationResult.Success(listOf(repeated, favorite)))
            dispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun `feedback strings include the server limit and all classified outcomes`() {
        val messages = listOf(
            SmileyFavoriteFeedback.Added to context.getString(R.string.editor_smiley_favorite_added),
            SmileyFavoriteFeedback.Removed to context.getString(R.string.editor_smiley_favorite_removed),
            SmileyFavoriteFeedback.AlreadyPresent to context.getString(R.string.editor_smiley_favorite_already_present),
            SmileyFavoriteFeedback.Full(7) to "Liste pleine (7 au plus) : retirez d'abord un favori",
            SmileyFavoriteFeedback.NotInFavorites to
                "Ce smiley ne figure pas dans vos favoris HFR : il ne peut pas en être retiré.",
            SmileyFavoriteFeedback.NotFound to context.getString(R.string.editor_smiley_favorite_not_found),
            SmileyFavoriteFeedback.ListChanged to context.getString(R.string.editor_smiley_favorite_list_changed),
            SmileyFavoriteFeedback.NotAuthenticated to
                context.getString(R.string.editor_smiley_favorite_session_expired),
            SmileyFavoriteFeedback.TransportFailure to context.getString(R.string.editor_smiley_favorite_network_error),
            SmileyFavoriteFeedback.UnknownResponse to
                context.getString(R.string.editor_smiley_favorite_unknown_response),
        )
        var feedback by mutableStateOf<SmileyFavoriteFeedback>(SmileyFavoriteFeedback.Added)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerSheet(
                    state = SmileyPickerState.Open(
                        favoriteFeedback = feedback, initialTab = SmileyPickerTab.FAVORITES,
                    ),
                    onDismiss = {}, onQueryChange = {}, onSmileyClicked = {},
                )
            }
        }
        messages.forEach { (value, message) ->
            compose.runOnIdle { feedback = value }
            compose.onNodeWithText(message).assertExists()
        }
    }

    private fun assertFavoriteActions(smiley: EditorSmiley, labelResource: Int) {
        val label = context.getString(labelResource)
        val semantics = compose.onNodeWithContentDescription(description(smiley)).fetchSemanticsNode().config
        assertEquals(
            context.getString(R.string.editor_smiley_detail_show), semantics[SemanticsActions.OnLongClick].label,
        )
        assertEquals(listOf(context.getString(R.string.editor_smiley_detail_show), label),
            semantics[SemanticsActions.CustomActions].map { it.label })
    }

    private fun assertNoFavoriteActions(smiley: EditorSmiley) {
        val semantics = compose.onNodeWithContentDescription(description(smiley)).fetchSemanticsNode().config
        assertEquals(
            context.getString(R.string.editor_smiley_detail_show), semantics[SemanticsActions.OnLongClick].label,
        )
        assertEquals(listOf(context.getString(R.string.editor_smiley_detail_show)),
            semantics[SemanticsActions.CustomActions].map { it.label })
    }

    private fun backToGrid() {
        compose.onNodeWithContentDescription(context.getString(R.string.editor_smiley_detail_back)).performClick()
    }

    private fun showWiki(controller: SmileyPickerController) {
        controller.onQueryChanged("fictif")
        dispatcher.scheduler.advanceUntilIdle()
        // Pre-mount setup: restoring the search selects Wiki, but dismiss() also consumes visible feedback.
        controller.dismiss()
        controller.open()
    }

    private fun controller(
        favorites: List<EditorSmiley> = emptyList(),
        wikiItems: List<EditorSmiley> = listOf(wiki, wiki, other),
        add: suspend (String) -> HfrFavoriteSmileyMutationResult = {
            HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        },
        remove: suspend (String) -> HfrFavoriteSmileyMutationResult = {
            HfrFavoriteSmileyMutationResult.Success(emptyList())
        },
    ) = SmileyPickerController(
        scope = CoroutineScope(dispatcher),
        searchWiki = { _, _ -> wikiItems },
        favoriteSources = SmileyPickerFavoriteSources(
            favoriteSmileys = { favorites },
            hfrFavoriteMutations = SmileyFavoriteMutations(
                addHfrFavorite = { added += it; add(it) },
                removeHfrFavorite = { removed += it; remove(it) },
            ),
        ),
    ).also { it.open() }

    private fun description(smiley: EditorSmiley) =
        context.getString(R.string.editor_smiley_insert_description, smiley.token)

    private fun mount(controller: SmileyPickerController) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val state by controller.state.collectAsState()
                (state as? SmileyPickerState.Open)?.let { open ->
                    SmileyPickerSheet(
                        state = open,
                        onDismiss = controller::dismiss,
                        onQueryChange = controller::onQueryChanged,
                        onSmileyClicked = { inserted = it; controller.dismiss() },
                    )
                }
            }
        }
    }
}
