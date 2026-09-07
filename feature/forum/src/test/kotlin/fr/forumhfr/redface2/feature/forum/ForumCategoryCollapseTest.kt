package fr.forumhfr.redface2.feature.forum

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.domain.preferences.CategoryFlagFilter
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1303 — mounted 360 dp proof, including #1129 ordering and #1130 search focus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class ForumCategoryCollapseTest {
    @get:Rule val compose = createComposeRule()

    private var state by mutableStateOf(contentState())

    @Test
    fun `four layouts gain real list height and keep a single header before sticky topics`() {
        mount()
        compose.onNodeWithTag(FORUM_CATEGORY_CONTENT_TAG).assertWidthIsEqualTo(360.dp)
        val expandedList = listBounds()
        val expandedRegularTop = compose.onNodeWithText("Ordinaire 3").getUnclippedBoundsInRoot().top
        val headerBottom = compose.onNodeWithText("Épinglés (2)").getUnclippedBoundsInRoot().bottom
        val stickyTop = compose.onNodeWithText("Épinglé 1").getUnclippedBoundsInRoot().top
        assertTrue("header must precede the sticky group", headerBottom <= stickyTop)
        compose.onNodeWithText("Autres sujets").assertDoesNotExist()

        update { it.copy(stickyTopicsCollapsed = true) }
        compose.onNodeWithText("2 épinglés masqués").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Épinglé 1").assertDoesNotExist()
        assertTrue(compose.onNodeWithText("Ordinaire 3").getUnclippedBoundsInRoot().top < expandedRegularTop)
        assertEquals(expandedList, listBounds())

        update { it.copy(menusCollapsed = true, stickyTopicsCollapsed = false) }
        val collapsedList = listBounds()
        assertTrue("commands must free at least 100 dp", expandedList.top - collapsedList.top >= 100.dp)
        assertEquals(expandedList.bottom, collapsedList.bottom)
        compose.onNodeWithText("Épinglé 1").assertIsDisplayed()
        compose.onNodeWithContentDescription("Ouvrir la recherche").assertIsDisplayed()

        update { it.copy(stickyTopicsCollapsed = true) }
        compose.onNodeWithText("2 épinglés masqués").assertIsDisplayed()
        assertEquals(collapsedList, listBounds())
        compose.onNodeWithText("2 épinglés masqués").performClick()
        compose.onNodeWithText("Épinglé 1").assertIsDisplayed()
        println("#1303 360dp list: expanded=$expandedList collapsed=$collapsedList")
    }

    @Test
    fun `long title and large font keep full semantics and separate 48 dp controls`() {
        state = state.copy(menusCollapsed = true, categoryName = LONG_TITLE)
        mount(fontScale = 2f)
        compose.onNodeWithText(LONG_TITLE).assertIsDisplayed()
        val search = compose.onNodeWithContentDescription("Ouvrir la recherche")
        val menus = compose.onNodeWithContentDescription("Développer les menus")
        val searchBounds = search.getUnclippedBoundsInRoot()
        val menuBounds = menus.getUnclippedBoundsInRoot()
        assertTrue(searchBounds.right <= menuBounds.left)
        assertTrue((menuBounds.right - menuBounds.left) >= 48.dp && (menuBounds.bottom - menuBounds.top) >= 48.dp)
        assertTrue((searchBounds.right - searchBounds.left) >= 48.dp)
        assertTrue((searchBounds.bottom - searchBounds.top) >= 48.dp)
        menus.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Repliés"))
        menus.performClick()
        compose.onNodeWithContentDescription("Réduire les menus")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Dépliés"))
    }

    @Test
    fun `magnifier opens search without expanding menus and focus survives both menu transitions`() {
        state = state.copy(menusCollapsed = true, stickyTopicsCollapsed = true)
        mount()
        compose.onNodeWithContentDescription("Ouvrir la recherche").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Épinglé 1").assertIsDisplayed()
        compose.onNodeWithText("Épinglés (2)").assert(noClick())
        compose.onNodeWithContentDescription("Développer les menus").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("Épinglé 1")
        compose.onNodeWithContentDescription("Réduire les menus").performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("Épinglé 1", state.searchQuery)
        compose.onNodeWithText("Épinglé (1)").assertIsDisplayed().assert(noClick())
        compose.onNodeWithContentDescription("Fermer la recherche").performClick()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals("", state.searchQuery)
        compose.onNodeWithText("2 épinglés masqués").assertIsDisplayed()
    }

    @Test
    fun `active summary reopens menus and unknown subcategory keeps its id`() {
        state = state.copy(menusCollapsed = true, selectedSubcat = SUBCAT, flagFilter = CategoryFlagFilter.FAVORITES)
        mount()
        compose.onNodeWithText("Android · Favoris").assertHasClickAction().performClick()
        compose.onNodeWithContentDescription("Réduire les menus").assertIsDisplayed()
        update { it.copy(menusCollapsed = true, subcategories = SubcategoriesUiState.Loading) }
        compose.onNodeWithText("Sous-catégorie 550 · Favoris").assertIsDisplayed().assertHasClickAction()
        update { it.copy(selectedSubcat = null, flagFilter = CategoryFlagFilter.ALL) }
        compose.onNodeWithText("Sous-catégorie 550 · Favoris").assertDoesNotExist()
    }

    @Test
    fun `empty regular-only sticky-only and mixed pages obey the header and pager contract`() {
        state = contentState(emptyList()).copy(menusCollapsed = true)
        mount()
        compose.onNodeWithText("Aucun topic à afficher.").assertIsDisplayed()
        compose.onNodeWithText("Épinglés (2)").assertDoesNotExist()
        replaceTopics(listOf(topic(3)))
        compose.onNodeWithText("Ordinaire 3").assertIsDisplayed()
        compose.onNodeWithText("Épinglé (1)").assertDoesNotExist()
        replaceTopics(listOf(topic(1, sticky = true)))
        compose.onNodeWithText("Épinglé (1)").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Épinglé 1").assertIsDisplayed()
        compose.onNodeWithText("Épinglé (1)").performClick()
        compose.onNodeWithText("1 épinglé masqué").assertIsDisplayed()
        compose.onNodeWithText("Aucun topic à afficher.").assertDoesNotExist()
        compose.onNodeWithText("page 1 / 3").assertIsDisplayed()
    }

    @Test
    fun `search zero results and sticky-only match expose no collapse command`() {
        state = state.copy(menusCollapsed = true, stickyTopicsCollapsed = true)
        mount()
        compose.onNodeWithContentDescription("Ouvrir la recherche").performClick()
        compose.onNodeWithText("Épinglés (2)").assert(noClick())
        compose.onNode(hasSetTextAction()).performTextInput("introuvable")
        compose.onNodeWithText("Épinglés (2)").assertDoesNotExist()
        compose.onNodeWithText("Aucun topic ne contient « introuvable » dans cette page.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Effacer la recherche").performClick()
        compose.onNodeWithText("Épinglé 1").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `flag buckets stay flat with sticky topics even when collapse is preferred`() {
        val topics = listOf(topic(3), topic(1, sticky = true), topic(4))
        state = contentState(topics).copy(
            menusCollapsed = true,
            stickyTopicsCollapsed = true,
            flagFilter = CategoryFlagFilter.READ,
            flagFilterTopics = TopicsUiState.Content(page(topics)),
        )
        mount()
        val regularTop = compose.onNodeWithText("Ordinaire 3").getUnclippedBoundsInRoot().top
        val stickyTop = compose.onNodeWithText("Épinglé 1").getUnclippedBoundsInRoot().top
        assertTrue(regularTop < stickyTop)
        compose.onNodeWithText("Épinglé (1)").assertDoesNotExist()
        compose.onNodeWithText("1 épinglé masqué").assertDoesNotExist()
        compose.onNodeWithText("page 1 / 3").assertDoesNotExist()
    }

    @Test
    fun `hydration only shows title and loading with disabled menu command`() {
        state = state.copy(layoutPreferencesReady = false)
        mount()
        compose.onNodeWithText(CATEGORY_NAME).assertIsDisplayed()
        compose.onNodeWithContentDescription("Réduire les menus").assertIsNotEnabled()
        compose.onNodeWithText("Android").assertDoesNotExist()
        compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).assertDoesNotExist()
        update { it.copy(layoutPreferencesReady = true, menusCollapsed = true, stickyTopicsCollapsed = true) }
        compose.onNodeWithText("2 épinglés masqués").assertIsDisplayed()
        compose.onNodeWithText("Android").assertDoesNotExist()
    }

    @Test
    fun `anonymous users can collapse both groups without a flag selector or FAB`() {
        state = state.copy(canCreateTopic = false)
        mount()
        compose.onNodeWithText("Favoris").assertDoesNotExist()
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Nouveau topic").assertDoesNotExist()
        compose.onNodeWithContentDescription("Réduire les menus").performClick()
        compose.onNodeWithText("Épinglés (2)").performClick()
        compose.onNodeWithText("2 épinglés masqués").assertIsDisplayed()
    }

    @Test
    fun `regular scroll anchor survives sticky collapse and expansion`() {
        mount()
        compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).performScrollToIndex(5)
        val before = compose.onNodeWithText("Ordinaire 5").getUnclippedBoundsInRoot().top
        update { it.copy(stickyTopicsCollapsed = true) }
        assertEquals(before, compose.onNodeWithText("Ordinaire 5").getUnclippedBoundsInRoot().top)
        update { it.copy(stickyTopicsCollapsed = false) }
        assertEquals(before, compose.onNodeWithText("Ordinaire 5").getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `removed sticky anchor returns to header and FAB expands only at offset zero`() {
        mount()
        // Material 3 clears the extended label's merged semantics; inspect its actual text node.
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).performScrollToIndex(2)
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertDoesNotExist()
        update { it.copy(stickyTopicsCollapsed = true) }
        assertEquals(listBounds().top, compose.onNodeWithText("2 épinglés masqués").getUnclippedBoundsInRoot().top)
        assertEquals(
            0f,
            compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value(),
        )
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Nouveau topic").assertIsDisplayed().assertHasClickAction()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/forum_category_collapse_anchor.png")
        compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, 24f)
        }
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Nouveau topic").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).performScrollToIndex(0)
        compose.onNodeWithText("Nouveau topic", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun recordLargeFontCollapsed() {
        state = state.copy(menusCollapsed = true, stickyTopicsCollapsed = true, categoryName = LONG_TITLE)
        mount(fontScale = 2f)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/forum_category_collapse_large_font.png")
    }

    private fun mount(fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                    ForumCategoryContent(
                        state = state,
                        highlightTitle = null,
                        onOpenTopic = {},
                        onCreateTopic = { _, _ -> },
                        callbacks = ForumCategoryCallbacks(
                            onSetMenusCollapsed = { state = state.copy(menusCollapsed = it) },
                            onSetStickyTopicsCollapsed = { state = state.copy(stickyTopicsCollapsed = it) },
                            onOpenSearch = { state = state.copy(searchActive = true) },
                            onCloseSearch = { search(query = "", active = false) },
                            onQueryChange = { search(query = it, active = true) },
                        ),
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun search(query: String, active: Boolean) {
        val topics = (state.topics as TopicsUiState.Content).page.topics
        state = state.copy(
            searchQuery = query,
            searchActive = active,
            filteredTopics = topics.filter { matchesTopicQuery(it, query) },
        )
    }

    private fun update(transform: (CategoryUiState) -> CategoryUiState) {
        compose.runOnIdle { state = transform(state) }
        compose.waitForIdle()
    }

    private fun replaceTopics(topics: List<TopicSummary>) = update {
        it.copy(topics = TopicsUiState.Content(page(topics)), filteredTopics = topics)
    }

    private fun listBounds() = compose.onNodeWithTag(FORUM_CATEGORY_LIST_TAG).getUnclippedBoundsInRoot()
    private fun noClick() = SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick)

    private companion object {
        const val CATEGORY_NAME = "Technologies Mobiles"
        const val LONG_TITLE = "Technologies Mobiles et toutes les discussions de la catégorie"
        const val CAT = 23
        const val SUBCAT = 550

        fun contentState(topics: List<TopicSummary> = (1..30).map { topic(it, sticky = it <= 2) }) = CategoryUiState(
            cat = CAT,
            categoryName = CATEGORY_NAME,
            initialSubcat = null,
            selectedSubcat = null,
            page = 1,
            pageCount = 3,
            subcategories = SubcategoriesUiState.Content(listOf(SubCategory(SUBCAT, "Android", CAT))),
            topics = TopicsUiState.Content(page(topics)),
            searchQuery = "",
            filteredTopics = topics,
            isRefreshing = false,
            canCreateTopic = true,
            layoutPreferencesReady = true,
        )

        fun page(topics: List<TopicSummary>) = TopicListPage(
            cat = CAT, subcat = null, page = 1, resultsPerPage = 30, totalTopics = 90, topics = topics,
        )

        fun topic(id: Int, sticky: Boolean = false) = TopicSummary(
            cat = CAT,
            subcat = null,
            topicId = id,
            title = if (sticky) "Épinglé $id" else "Ordinaire $id",
            author = "auteur",
            lastReplyAuthor = "auteur",
            lastReplyAt = "",
            replyCount = 1,
            totalPages = 1,
            isSticky = sticky,
            isLocked = false,
            hasUnread = null,
            lastReadPage = null,
            lastPostReadId = null,
            flagType = null,
        )
    }
}
