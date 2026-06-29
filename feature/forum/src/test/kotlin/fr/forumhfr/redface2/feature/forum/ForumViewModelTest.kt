package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
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
    fun `uiState classifies an HFR 5xx Failure on the REST path as ServerDown`() = runTest {
        // #324 — the categories fetch goes through HfrApiClient (REST); a 500 there must
        // be presented as « HFR est en panne », not as a connectivity problem.
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(
                ForumResult.Failure(
                    HfrServerException(code = 500, url = "https://forum.hardware.fr/webservices/rest_api.php"),
                ),
            )
            val error = awaitItem() as ForumUiState.Error
            assertEquals(HfrErrorKind.ServerDown, error.kind)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState classifies a transport failure as Network`() = runTest {
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Failure(UnknownHostException("forum.hardware.fr")))
            val error = awaitItem() as ForumUiState.Error
            assertEquals(HfrErrorKind.Network, error.kind)
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

    @Test
    fun `isRefreshing stays false on cold start before any user-triggered refresh`() = runTest {
        // Pins the contract that `isRefreshing` is reserved for user-driven refreshes —
        // initial cold-start fetch goes through `uiState.Loading`, not `isRefreshing=true`.
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.isRefreshing.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isRefreshing flips true during refresh and false after`() = runTest {
        val repo = FakeForumRepository()
        val vm = ForumViewModel(repo)

        vm.isRefreshing.test {
            assertEquals(false, awaitItem())

            // Gate refreshCategories so the launched refresh coroutine suspends
            // mid-way. The flag must be true while the gate holds.
            val gate = CompletableDeferred<Unit>()
            repo.suspendRefreshCategoriesUntil = gate

            vm.refresh()

            assertEquals(true, awaitItem())

            // Release the gate → finally branch flips the flag back off.
            gate.complete(Unit)
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
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
        var suspendRefreshCategoriesUntil: CompletableDeferred<Unit>? = null

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            categories.asSharedFlow()

        override suspend fun getCategories(forceRefreshIfStale: Boolean): ForumResult<List<Category>> =
            categories.replayCache.lastOrNull { it !is ForumResult.Loading }
                ?: ForumResult.Success(emptyList())

        override suspend fun refreshCategories() {
            refreshCategoriesCalled = true
            suspendRefreshCategoriesUntil?.await()
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

        override suspend fun prefetchTopicList(cat: Int, subcat: Int?, page: Int) = Unit

        // #455 — not exercised by ForumViewModel (Forum home lists categories, not flags).
        override suspend fun getFlagFilteredTopics(
            cat: Int,
            subcat: Int?,
            bucket: FlagFilterBucket,
        ): ForumResult<TopicListPage> = ForumResult.Failure(UnsupportedOperationException())

        suspend fun emitCategories(result: ForumResult<List<Category>>) {
            categories.emit(result)
        }
    }
}
