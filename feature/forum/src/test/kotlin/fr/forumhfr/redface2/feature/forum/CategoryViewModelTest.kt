package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.TopicSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CategoryViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectSubcategory swaps the topic listing and resets to page 1`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            // Initial state — Loading on subcategories AND topics
            val initial = awaitItem()
            assertEquals(23, initial.cat)
            assertNull(initial.selectedSubcat)
            assertEquals(1, initial.page)

            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(cat = 23, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            // Drain warm-up emissions until both subcategories and topics are Content.
            do {
                val s = awaitItem()
                if (s.subcategories is SubcategoriesUiState.Content && s.topics is TopicsUiState.Content) break
            } while (true)

            vm.selectSubcategory(550)

            // After selection, topics goes Loading until the new subcat list arrives.
            val afterSelect = awaitItem()
            assertEquals(550, afterSelect.selectedSubcat)
            assertEquals(1, afterSelect.page)

            repo.emitTopicList(cat = 23, subcat = 550, page = 1, result = ForumResult.Success(EMPTY_PAGE))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectPage moves to the requested page`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 13, initialSubcat = 422),
            forumRepository = repo,
        )

        vm.uiState.test {
            // Drain until topics state is Content for page 1, then jump to page 2.
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            repo.emitTopicList(cat = 13, subcat = 422, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            var current = awaitItem()
            while (current.topics !is TopicsUiState.Content) current = awaitItem()
            assertEquals(1, current.page)

            vm.selectPage(2)
            // Page advance triggers a new flow subscription — emit content for page 2.
            repo.emitTopicList(cat = 13, subcat = 422, page = 2, result = ForumResult.Success(EMPTY_PAGE))

            // Drain until we see the page=2 payload back through uiState
            do {
                current = awaitItem()
            } while (current.page != 2 || current.topics !is TopicsUiState.Content)
            assertEquals(2, current.page)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to ForumRepository for subcategories AND current topic list`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
        )

        vm.refresh()

        assertTrue(repo.refreshSubcategoriesCalls.contains(23))
        assertEquals(listOf(Triple(23, 550, 1)), repo.refreshTopicListCalls)
    }

    @Test
    fun `failure from observeSubcategories surfaces as SubcategoriesUiState Error`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            // Throw away initial Loading
            awaitItem()
            repo.emitSubcategories(ForumResult.Failure(IllegalStateException("subcats fail")))
            repo.emitTopicList(cat = 23, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            // Drain until subcategories is Error
            var current = awaitItem()
            while (current.subcategories !is SubcategoriesUiState.Error) current = awaitItem()
            assertEquals(
                "subcats fail",
                (current.subcategories as SubcategoriesUiState.Error).message,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        val SUBCAT_550 = SubCategory(id = 550, name = "Android", parentCategoryId = 23)
        val EMPTY_PAGE = TopicListPage(
            cat = 23,
            subcat = 550,
            page = 1,
            resultsPerPage = 50,
            totalTopics = 0,
            topics = emptyList<TopicSummary>(),
        )

        @Suppress("unused") // used to silence Category warning (not referenced directly)
        val UNUSED_CATEGORY: Category? = null
    }

    private class FakeForumRepository : ForumRepository {
        private val subcategories: MutableSharedFlow<ForumResult<List<SubCategory>>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        private val topicLists: MutableMap<
            Triple<Int, Int?, Int>,
            MutableSharedFlow<ForumResult<TopicListPage>>,
            > = mutableMapOf()

        var refreshSubcategoriesCalls: List<Int> = emptyList()
            private set
        var refreshTopicListCalls: List<Triple<Int, Int?, Int>> = emptyList()
            private set

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            MutableSharedFlow<ForumResult<List<Category>>>(replay = 1).asSharedFlow()

        override suspend fun refreshCategories() = Unit

        override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> =
            subcategories.asSharedFlow()

        override suspend fun refreshSubcategories(cat: Int) {
            refreshSubcategoriesCalls = refreshSubcategoriesCalls + cat
        }

        override fun observeTopicList(
            cat: Int,
            subcat: Int?,
            page: Int,
        ): Flow<ForumResult<TopicListPage>> =
            topicListFlow(cat, subcat, page).asSharedFlow()

        override suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int) {
            refreshTopicListCalls = refreshTopicListCalls + Triple(cat, subcat, page)
        }

        suspend fun emitSubcategories(result: ForumResult<List<SubCategory>>) {
            subcategories.emit(result)
        }

        suspend fun emitTopicList(
            cat: Int,
            subcat: Int?,
            page: Int,
            result: ForumResult<TopicListPage>,
        ) {
            topicListFlow(cat, subcat, page).emit(result)
        }

        private fun topicListFlow(
            cat: Int,
            subcat: Int?,
            page: Int,
        ): MutableSharedFlow<ForumResult<TopicListPage>> = topicLists.getOrPut(Triple(cat, subcat, page)) {
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        }
    }
}
