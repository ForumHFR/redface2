package fr.forumhfr.redface2.core.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.test.FakeImageLoaderEngine
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.EditorSmiley
import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import fr.forumhfr.redface2.core.ui.R
import fr.forumhfr.redface2.core.ui.RedfaceTheme
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
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SmileyWikiKeywordsContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val perso = EditorSmiley("[:ticento:6]", "https://example.invalid/ticento.gif", EditorSmileySource.WIKI)
    private val builtin = BUILTIN_HFR_SMILEYS.first()
    private var state by mutableStateOf(SmileyPickerState.Open(detailSmiley = perso))

    @OptIn(coil3.annotation.DelicateCoilApi::class)
    @Before
    fun images() {
        val engine = FakeImageLoaderEngine.Builder().apply {
            listOf(perso, builtin).forEach {
                intercept(it.imageUrl, ColorImage(0xFF808080.toInt(), width = 70, height = 50))
            }
        }.build()
        SingletonImageLoader.setUnsafe(ImageLoader.Builder(context).components { add(engine) }.build())
    }

    @Test
    fun `loading, loaded, empty and failure states retain an accessible section title`() {
        state = state.copy(wikiKeywords = SmileyWikiKeywordsState.Loading)
        mount()
        title().assertIsDisplayed().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        text(R.string.editor_smiley_wiki_keywords_loading).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(wikiKeywords = SmileyWikiKeywordsState.Loaded(listOf("feu", "cramé"))) }
        compose.onNodeWithText("cramé").assertIsDisplayed().assert(
            SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick),
        ).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Role))
        text(R.string.editor_smiley_wiki_keywords_loading).assertDoesNotExist()
        text(R.string.editor_smiley_wiki_keywords_retry).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(wikiKeywords = SmileyWikiKeywordsState.Loaded(emptyList())) }
        text(R.string.editor_smiley_wiki_keywords_empty).assertIsDisplayed()
        compose.onNodeWithText("cramé").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(wikiKeywords = SmileyWikiKeywordsState.Failed) }
        title().assertIsDisplayed()
        text(R.string.editor_smiley_wiki_keywords_failed).assertIsDisplayed()
        text(R.string.editor_smiley_wiki_keywords_retry).assertIsDisplayed()
        text(R.string.editor_smiley_wiki_keywords_empty).assertDoesNotExist()
    }

    @Test
    fun `overflow is limited to two measured rows and can be expanded and collapsed`() {
        val keywords = (1..18).map { "mot-clé-$it" }
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.width(328.dp)) {
                    SmileyWikiKeywordsContent(SmileyWikiKeywordsState.Loaded(keywords)) {}
                }
            }
        }
        val heading = title().getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText(keywords.first()).getUnclippedBoundsInRoot()
        assertTrue(heading.bottom <= first.top)
        val visible = keywords.map { compose.onNodeWithText(it) }.filter { it.isDisplayed() }
        assertEquals(2, visible.map { it.getUnclippedBoundsInRoot().top }.distinct().size)
        compose.onNodeWithText(keywords.last()).assertIsNotDisplayed()
        val expand = context.getString(R.string.editor_smiley_wiki_keywords_expand, keywords.size)
        compose.onNodeWithText(expand).assertIsDisplayed().performClick()
        compose.onNodeWithText(keywords.last()).assertIsDisplayed()
        text(R.string.editor_smiley_wiki_keywords_collapse).performClick()
        compose.onNodeWithText(keywords.last()).assertIsNotDisplayed()
        compose.onNodeWithText(expand).assertIsDisplayed()
    }

    @Test
    fun `all commands fit without scrolling at 360 by 760 with ten keywords and both memberships`() {
        state = state.copy(
            favoriteSmileys = listOf(perso), localFavoriteTokens = setOf(perso.token),
            onLocalFavoriteChange = {}, onFavoriteChange = {},
            wikiKeywords = SmileyWikiKeywordsState.Loaded(
                listOf("feu", "feux", "fire", "brule", "bruler", "burn", "chaud", "hot", "cramer", "cramé"),
            ),
        )
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyPickerSheet(state, {}, {}, {})
            }
        }
        val viewport = compose.onNodeWithTag("smiley_detail_viewport")
        val bounds = viewport.getUnclippedBoundsInRoot()
        listOf(
            R.string.editor_smiley_detail_insert, R.string.editor_smiley_detail_copy,
            R.string.editor_smiley_local_favorite_remove, R.string.editor_smiley_favorite_remove,
        ).forEach { resource ->
            val action = text(resource).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$resource top=$action viewport=$bounds", action.top >= bounds.top)
            assertTrue("$resource bottom=$action viewport=$bounds", action.bottom <= bounds.bottom)
        }
        val scroll = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(0f, scroll.value(), 0f)
        val lastAction = text(R.string.editor_smiley_favorite_remove).getUnclippedBoundsInRoot()
        assertTrue(lastAction.bottom <= title().getUnclippedBoundsInRoot().top)
    }

    @Test
    fun `short keyword lists have no expansion affordance`() {
        state = state.copy(wikiKeywords = SmileyWikiKeywordsState.Loaded(listOf("feu", "hot")))
        mount()
        compose.onNodeWithText(context.getString(R.string.editor_smiley_wiki_keywords_expand, 2)).assertDoesNotExist()
        text(R.string.editor_smiley_wiki_keywords_collapse).assertDoesNotExist()
    }

    @Test
    fun `keywords have finite bounds when the parent width is unbounded`() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                Box(Modifier.width(328.dp).wrapContentWidth(unbounded = true)) {
                    SmileyWikiKeywordsContent(SmileyWikiKeywordsState.Loaded(listOf("feu", "hot"))) {}
                }
            }
        }
        val first = compose.onNodeWithText("feu").assertIsDisplayed().getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText("hot").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals(first.top, last.top)
        assertTrue(last.right > first.right)
    }

    @Config(qualifiers = "w760dp-h360dp-xxhdpi")
    @Test
    fun `landscape with large font keeps every command reachable by scrolling`() {
        state = state.copy(onLocalFavoriteChange = {}, onFavoriteChange = {})
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    SmileyPickerSheet(state, {}, {}, {})
                }
            }
        }
        listOf(
            R.string.editor_smiley_detail_insert, R.string.editor_smiley_detail_copy,
            R.string.editor_smiley_local_favorite_add, R.string.editor_smiley_favorite_add,
        ).forEach { text(it).performScrollTo().assertIsDisplayed() }
    }

    @Test
    fun `builtin has no Wiki section even if given stale keyword state`() {
        state = state.copy(detailSmiley = builtin, wikiKeywords = SmileyWikiKeywordsState.Loaded(listOf("feu")))
        mount()
        title().assertDoesNotExist()
        compose.onNodeWithText("feu").assertDoesNotExist()
        text(R.string.editor_smiley_wiki_keywords_retry).assertDoesNotExist()
    }

    @Test
    fun `retry button invokes the controller and replaces failure with new keywords`() {
        val dispatcher = StandardTestDispatcher()
        var calls = 0
        val controller = SmileyPickerController(
            CoroutineScope(dispatcher), { _, _ -> emptyList() },
            wikiKeywords = SmileyWikiKeywordsSource(load = {
                calls++
                if (calls == 1) SmileyWikiKeywordsResult.Failed else SmileyWikiKeywordsResult.Loaded(listOf("feu"))
            }),
        )
        controller.open()
        controller.onDetailChanged(perso)
        compose.setContent {
            val current by controller.state.collectAsState()
            val open = current as SmileyPickerState.Open
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyDetailContent(perso, open, null, {})
            }
        }
        text(R.string.editor_smiley_wiki_keywords_loading).assertIsDisplayed()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        text(R.string.editor_smiley_wiki_keywords_retry).performScrollTo().performClick()
        text(R.string.editor_smiley_wiki_keywords_loading).assertIsDisplayed()
        compose.runOnIdle { dispatcher.scheduler.runCurrent() }
        compose.onNodeWithText("feu").assertIsDisplayed()
        text(R.string.editor_smiley_wiki_keywords_failed).assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    private fun mount() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                SmileyDetailContent(requireNotNull(state.detailSmiley), state, null, {})
            }
        }
    }

    private fun title() = text(R.string.editor_smiley_wiki_keywords_title)
    private fun text(resource: Int) = compose.onNodeWithText(context.getString(resource))
}
