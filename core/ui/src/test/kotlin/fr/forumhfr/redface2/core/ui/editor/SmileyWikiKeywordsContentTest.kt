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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
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
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
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
    fun `keywords wrap below memberships before the insertion action`() {
        val keywords = (1..18).map { "mot-clé-$it" }
        state = state.copy(
            wikiKeywords = SmileyWikiKeywordsState.Loaded(keywords), localFavoriteTokens = setOf(perso.token),
        )
        mount()
        val membership = text(R.string.editor_smiley_detail_local_favorite).getUnclippedBoundsInRoot()
        val heading = title().getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText(keywords.first()).getUnclippedBoundsInRoot()
        val last = compose.onNodeWithText(keywords.last()).getUnclippedBoundsInRoot()
        val insert = text(R.string.editor_smiley_detail_insert).getUnclippedBoundsInRoot()
        assertTrue(membership.bottom <= heading.top)
        assertTrue(heading.bottom <= first.top)
        assertTrue(first.top < last.top)
        assertTrue(last.bottom <= insert.top)
        text(R.string.editor_smiley_detail_insert).performScrollTo().assertIsDisplayed()
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
