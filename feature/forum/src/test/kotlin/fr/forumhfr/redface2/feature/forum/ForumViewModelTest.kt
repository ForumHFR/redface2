package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForumViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState walks Loading then Content as the categories load`() = runTest {
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES))
            assertEquals(ForumUiState.Content(SAMPLE_CATEGORIES), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState surfaces a Failure as Error preserving the message`() = runTest {
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Failure(IllegalStateException("HFR down")))
            assertEquals(ForumUiState.Error("HFR down"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to ForumRepository refreshCategories`() = runTest {
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.refresh()

        assertTrue(repo.refreshCategoriesCalled)
    }

    private companion object {
        val SAMPLE_CATEGORIES: List<Category> = listOf(
            Category(id = 13, name = "Discussions", forceSubcat = true, subcategoryCount = 15),
            Category(id = 23, name = "Technologies Mobiles", forceSubcat = true, subcategoryCount = 10),
        )
    }

    private class FakeForumRepository : ForumRepository {
        private val categories: MutableSharedFlow<ForumResult<List<Category>>> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        var refreshCategoriesCalled: Boolean = false
            private set

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            categories.asSharedFlow()

        override suspend fun refreshCategories() {
            refreshCategoriesCalled = true
        }

        override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> =
            MutableSharedFlow<ForumResult<List<SubCategory>>>(replay = 1).asSharedFlow()

        override suspend fun refreshSubcategories(cat: Int) = Unit

        override fun observeTopicList(
            cat: Int,
            subcat: Int?,
            page: Int,
        ): Flow<ForumResult<TopicListPage>> =
            MutableSharedFlow<ForumResult<TopicListPage>>(replay = 1).asSharedFlow()

        override suspend fun refreshTopicList(cat: Int, subcat: Int?, page: Int) = Unit

        suspend fun emitCategories(result: ForumResult<List<Category>>) {
            categories.emit(result)
        }
    }
}
