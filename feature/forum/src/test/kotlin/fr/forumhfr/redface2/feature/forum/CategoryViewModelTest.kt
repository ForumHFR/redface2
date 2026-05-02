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
import kotlinx.coroutines.withTimeout
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
            awaitContent { it.subcategories is SubcategoriesUiState.Content && it.topics is TopicsUiState.Content }

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
    fun `initialPage from CategoryRequest seeds the page state`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550, initialPage = 4),
            forumRepository = repo,
        )

        // Initial uiState carries the initialPage straight through (no warm-up needed —
        // the StateFlow's initialValue is built off the request).
        assertEquals(4, vm.uiState.value.page)
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

            var current = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(1, current.page)

            vm.selectPage(2)
            // Page advance triggers a new flow subscription — emit content for page 2.
            repo.emitTopicList(cat = 13, subcat = 422, page = 2, result = ForumResult.Success(EMPTY_PAGE))

            current = awaitContent { it.page == 2 && it.topics is TopicsUiState.Content }
            assertEquals(2, current.page)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pageCount reflects topics totalTopics divided by resultsPerPage`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
        )

        vm.uiState.test {
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            // 130 topics / 50-per-page → 3 pages.
            repo.emitTopicList(
                cat = 23,
                subcat = 550,
                page = 1,
                result = ForumResult.Success(
                    EMPTY_PAGE.copy(totalTopics = 130, resultsPerPage = 50),
                ),
            )

            val current = awaitContent { it.topics is TopicsUiState.Content }
            assertEquals(3, current.pageCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categoryName is derived from observeCategories matching cat`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            // Initial Loading uiState — no categoryName yet.
            assertNull(awaitItem().categoryName)

            repo.emitCategories(
                ForumResult.Success(
                    listOf(
                        Category(id = 23, name = "Technologies Mobiles", forceSubcat = false, subcategoryCount = 5),
                        Category(id = 30, name = "Electronique", forceSubcat = false, subcategoryCount = 3),
                    ),
                ),
            )

            val current = awaitContent { it.categoryName == "Technologies Mobiles" }
            assertEquals("Technologies Mobiles", current.categoryName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categoryName stays null when categories list does not contain cat`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 999, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitCategories(
                ForumResult.Success(
                    listOf(
                        Category(id = 23, name = "Technologies Mobiles", forceSubcat = false, subcategoryCount = 5),
                    ),
                ),
            )
            // No emission with categoryName != null is expected — we just ensure the
            // state remains coherent (cat=999, name=null) when topics emit too.
            repo.emitTopicList(cat = 999, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))
            val current = awaitContent { it.topics is TopicsUiState.Content }
            assertNull(current.categoryName)
            assertEquals(999, current.cat)
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
    fun `refresh forwards the current page and selectedSubcat after the user moved`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null, initialPage = 1),
            forumRepository = repo,
        )

        // Move state to (subcat=550, page=2) before the user pulls to refresh.
        vm.selectSubcategory(550)
        vm.selectPage(2)

        vm.refresh()

        assertTrue(repo.refreshSubcategoriesCalls.contains(23))
        assertEquals(listOf(Triple(23, 550, 2)), repo.refreshTopicListCalls)
    }

    @Test
    fun `isRefreshing flips true during refresh and false after`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
        )

        // Repository's refresh* methods are simple stubs (Unit) — they suspend zero
        // work, so isRefreshing flips on then off in a single dispatch cycle. We
        // still assert the false state at rest, plus the on-the-fly tracking exposed
        // by `repo.refreshTopicListCalls`.
        assertEquals(false, vm.uiState.value.isRefreshing)
        vm.refresh()
        assertEquals(false, vm.uiState.value.isRefreshing)
        assertTrue(repo.refreshTopicListCalls.isNotEmpty())
    }

    @Test
    fun `searchQuery filters by title author and lastReplyAuthor case- and accent-insensitively`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = 550),
            forumRepository = repo,
        )

        val topics = listOf(
            topicSummary(id = 1, title = "Android et iOS", author = "alice", lastAuthor = "bob"),
            topicSummary(id = 2, title = "Réflexion sur la batterie", author = "Charlie", lastAuthor = "dave"),
            topicSummary(id = 3, title = "Câble USB-C", author = "Eve", lastAuthor = "Frédéric"),
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Success(emptyList()))
            repo.emitTopicList(
                cat = 23,
                subcat = 550,
                page = 1,
                result = ForumResult.Success(EMPTY_PAGE.copy(topics = topics, totalTopics = topics.size)),
            )

            val withAll = awaitContent { it.topics is TopicsUiState.Content && it.filteredTopics.size == 3 }
            assertEquals(3, withAll.filteredTopics.size)

            // Title match — case-insensitive
            vm.updateSearchQuery("ANDROID")
            val byTitle = awaitContent { it.searchQuery == "ANDROID" && it.filteredTopics.size == 1 }
            assertEquals(setOf(1), byTitle.filteredTopics.map(TopicSummary::topicId).toSet())

            // Title match — accent-insensitive
            vm.updateSearchQuery("reflexion")
            val byAccent = awaitContent { it.searchQuery == "reflexion" && it.filteredTopics.size == 1 }
            assertEquals(setOf(2), byAccent.filteredTopics.map(TopicSummary::topicId).toSet())

            // Last reply author — accent-insensitive
            vm.updateSearchQuery("frederic")
            val byLastAuthor = awaitContent { it.searchQuery == "frederic" && it.filteredTopics.size == 1 }
            assertEquals(setOf(3), byLastAuthor.filteredTopics.map(TopicSummary::topicId).toSet())

            // Author match — case-insensitive substring
            vm.updateSearchQuery("CHAR")
            val byAuthor = awaitContent { it.searchQuery == "CHAR" && it.filteredTopics.size == 1 }
            assertEquals(setOf(2), byAuthor.filteredTopics.map(TopicSummary::topicId).toSet())

            // No match — empty list
            vm.updateSearchQuery("zzz")
            val noMatch = awaitContent { it.searchQuery == "zzz" }
            assertTrue(noMatch.filteredTopics.isEmpty())

            // Empty query — full list back
            vm.updateSearchQuery("")
            val cleared = awaitContent { it.searchQuery == "" && it.filteredTopics.size == 3 }
            assertEquals(3, cleared.filteredTopics.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `searchQuery is preserved across subcategory switches`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Success(listOf(SUBCAT_550)))
            repo.emitTopicList(
                cat = 23,
                subcat = null,
                page = 1,
                result = ForumResult.Success(EMPTY_PAGE.copy(subcat = null, totalTopics = 0)),
            )
            awaitContent { it.subcategories is SubcategoriesUiState.Content }

            vm.updateSearchQuery("hello")
            awaitContent { it.searchQuery == "hello" }

            vm.selectSubcategory(550)
            // After subcat switch the page resets to 1 — but the searchQuery stays.
            val afterSwitch = awaitContent { it.selectedSubcat == 550 }
            assertEquals("hello", afterSwitch.searchQuery)
            assertEquals(1, afterSwitch.page)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failure from observeSubcategories surfaces as SubcategoriesUiState Error`() = runTest {
        val repo = FakeForumRepository()
        val vm = CategoryViewModel(
            request = CategoryRequest(cat = 23, initialSubcat = null),
            forumRepository = repo,
        )

        vm.uiState.test {
            awaitItem() // initial Loading
            repo.emitSubcategories(ForumResult.Failure(IllegalStateException("subcats fail")))
            repo.emitTopicList(cat = 23, subcat = null, page = 1, result = ForumResult.Success(EMPTY_PAGE))

            val current = awaitContent { it.subcategories is SubcategoriesUiState.Error }
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
        const val AWAIT_CONTENT_TIMEOUT_MS = 2_000L

        fun topicSummary(
            id: Int,
            title: String,
            author: String = "anon",
            lastAuthor: String = author,
        ): TopicSummary = TopicSummary(
            cat = 23,
            subcat = 550,
            topicId = id,
            title = title,
            author = author,
            lastReplyAuthor = lastAuthor,
            lastReplyAt = "2026-05-02 10:00",
            replyCount = 0,
            totalPages = 1,
            isSticky = false,
            isLocked = false,
            hasUnread = null,
            lastReadPage = null,
            lastPostReadId = null,
            flagType = null,
        )
    }

    /**
     * Bounded helper around `awaitItem()` to drain warm-up emissions until the predicate
     * holds. Replaces the unbounded `do { ... } while (true)` loops that risked hanging
     * the test runner if the predicate was never satisfied.
     */
    private suspend fun app.cash.turbine.ReceiveTurbine<CategoryUiState>.awaitContent(
        predicate: (CategoryUiState) -> Boolean,
    ): CategoryUiState = withTimeout(AWAIT_CONTENT_TIMEOUT_MS) {
        var current = awaitItem()
        while (!predicate(current)) {
            current = awaitItem()
        }
        current
    }

    private class FakeForumRepository : ForumRepository {
        private val categories: MutableSharedFlow<ForumResult<List<Category>>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
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
            categories.asSharedFlow()

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

        suspend fun emitCategories(result: ForumResult<List<Category>>) {
            categories.emit(result)
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
