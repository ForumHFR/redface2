package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `wiki long press opens the add menu without inserting and selection adds`() {
        val controller = controller()
        showWiki(controller)
        mount(controller)
        assertFavoriteActions(wiki, R.string.editor_smiley_favorite_add)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }

        compose.runOnIdle {
            assertEquals(listOf(wiki.token), added)
            assertEquals(null, inserted)
            assertEquals(listOf(favorite), (controller.state.value as SmileyPickerState.Open).favoriteSmileys)
        }
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
        val removeAction = cell.fetchSemanticsNode().config[SemanticsActions.CustomActions].single().action
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
    fun `Standard favorite long press offers removal with the same accessibility label`() {
        val controller = controller(listOf(favorite))
        mount(controller)
        assertFavoriteActions(favorite, R.string.editor_smiley_favorite_remove)
        compose.onNodeWithContentDescription(description(favorite)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_remove)).performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.runOnIdle { assertEquals(listOf(favorite.token), removed) }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_removed)).assertExists()
    }

    @Test
    fun `builtin exposes no favorite gesture and keeps insertion`() {
        val controller = controller()
        mount(controller)
        val builtin = BUILTIN_HFR_SMILEYS.first()
        assertNoFavoriteActions(builtin)
        compose.onNodeWithContentDescription(description(builtin)).performClick()
        compose.runOnIdle {
            assertEquals(builtin.token, inserted)
            assertEquals(SmileyPickerState.Hidden, controller.state.value)
            assertTrue(added.isEmpty())
            assertTrue(removed.isEmpty())
        }
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
    fun `in flight disables every favorite gesture but permits insertion and retains a hidden failure`() {
        val gate = CompletableDeferred<HfrFavoriteSmileyMutationResult>()
        val controller = controller(add = { gate.await() })
        showWiki(controller)
        mount(controller)
        compose.onNodeWithContentDescription(description(wiki)).performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.editor_smiley_favorite_add)).performClick()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
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
                    state = SmileyPickerState.Open(favoriteFeedback = feedback),
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
        assertEquals(label, semantics[SemanticsActions.OnLongClick].label)
        assertEquals(listOf(label), semantics[SemanticsActions.CustomActions].map { it.label })
    }

    private fun assertNoFavoriteActions(smiley: EditorSmiley) {
        compose.onNodeWithContentDescription(description(smiley))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
    }

    private fun showWiki(controller: SmileyPickerController) {
        controller.onQueryChanged("fictif")
        dispatcher.scheduler.advanceUntilIdle()
    }

    private fun controller(
        favorites: List<EditorSmiley> = emptyList(),
        add: suspend (String) -> HfrFavoriteSmileyMutationResult = {
            HfrFavoriteSmileyMutationResult.Success(listOf(favorite))
        },
    ) = SmileyPickerController(
        scope = CoroutineScope(dispatcher),
        searchWiki = { _, _ -> listOf(wiki, wiki, other) },
        favoriteSmileys = { favorites },
        hfrFavoriteMutations = SmileyFavoriteMutations(
            addHfrFavorite = { added += it; add(it) },
            removeHfrFavorite = { removed += it; HfrFavoriteSmileyMutationResult.Success(emptyList()) },
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
