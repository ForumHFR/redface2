package fr.forumhfr.redface2.feature.forum

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumCategoriesUpdate
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionRecorder
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    fun `categories count first content and successful refresh but not errors or resubscription`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val repo = FakeForumRepository()
        val actions = mutableListOf<NetworkAction>()
        val vm = ForumViewModel(repo, NetworkActionRecorder { action, _, _ -> actions += action })
        val error = IllegalStateException("offline")

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Failure(error))
            assertEquals(ForumUiState.Error(error.message), awaitItem())
            assertTrue(actions.isEmpty())

            vm.refresh()
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES), isRefresh = true)
            val initial = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES, initial.categories)
            vm.onCategoriesPresented(initial.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST), actions)
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES.take(1)))
            val revalidated = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES.take(1), revalidated.categories)
            vm.onCategoriesPresented(revalidated.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST), actions)

            vm.refresh()
            repo.emitCategories(ForumResult.Failure(error), isRefresh = true)
            assertEquals(ForumUiState.Error(error.message), awaitItem())
            assertEquals(listOf(NetworkAction.LIST), actions)

            vm.refresh()
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES), isRefresh = true)
            val refreshed = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES, refreshed.categories)
            vm.onCategoriesPresented(refreshed.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST, NetworkAction.LIST), actions)
            cancelAndIgnoreRemainingEvents()
        }
        advanceTimeBy(6_000)
        runCurrent()
        repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES))

        vm.uiState.test {
            val cached = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES, cached.categories)
            vm.onCategoriesPresented(cached.listGeneration, isCurrentRoute = true)
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES.take(1)))
            val revalidated = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES.take(1), revalidated.categories)
            vm.onCategoriesPresented(revalidated.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST, NetworkAction.LIST), actions)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the second of two overlapping refreshes counts when the first fails`() = runTest {
        val repo = FakeForumRepository()
        val actions = mutableListOf<NetworkAction>()
        val vm = ForumViewModel(repo, NetworkActionRecorder { action, _, _ -> actions += action })
        val first = CompletableDeferred<ForumResult<List<Category>>>()
        val second = CompletableDeferred<ForumResult<List<Category>>>()

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES))
            val initial = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES, initial.categories)
            vm.onCategoriesPresented(initial.listGeneration, isCurrentRoute = true)
            actions.clear()

            repo.suspendRefreshCategoriesUntil = first
            vm.refresh()
            repo.suspendRefreshCategoriesUntil = second
            vm.refresh()
            assertEquals(2, repo.refreshCategoriesCalls)

            first.complete(ForumResult.Failure(IllegalStateException("offline")))
            assertEquals(ForumUiState.Error("offline"), awaitItem())
            assertTrue(actions.isEmpty())
            second.complete(ForumResult.Success(SAMPLE_CATEGORIES))
            val refreshed = awaitItem() as ForumUiState.Content
            assertEquals(SAMPLE_CATEGORIES, refreshed.categories)
            vm.onCategoriesPresented(refreshed.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST), actions)
        }
    }

    @Test
    fun `a refresh completed behind a category only counts when Forum is presented again`() = runTest {
        val repo = FakeForumRepository()
        val actions = mutableListOf<NetworkAction>()
        val vm = ForumViewModel(repo, NetworkActionRecorder { action, _, _ -> actions += action })
        val pendingRefresh = CompletableDeferred<ForumResult<List<Category>>>()
        repo.suspendRefreshCategoriesUntil = pendingRefresh

        vm.uiState.test {
            assertEquals(ForumUiState.Loading, awaitItem())
            repo.emitCategories(ForumResult.Success(SAMPLE_CATEGORIES))
            val initial = awaitItem() as ForumUiState.Content
            vm.onCategoriesPresented(initial.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST), actions)
            actions.clear()

            vm.refresh()
            assertTrue(vm.isRefreshing.value)
            // Opening a category removes the Forum UI collector while the upstream is still shared.
            cancelAndIgnoreRemainingEvents()
        }

        pendingRefresh.complete(ForumResult.Success(SAMPLE_CATEGORIES))
        runCurrent()
        val refreshed = vm.uiState.value as ForumUiState.Content
        vm.onCategoriesPresented(refreshed.listGeneration, isCurrentRoute = false)
        assertTrue(actions.isEmpty())

        vm.uiState.test {
            assertEquals(refreshed, awaitItem())
            vm.onCategoriesPresented(refreshed.listGeneration, isCurrentRoute = true)
            vm.onCategoriesPresented(refreshed.listGeneration, isCurrentRoute = true)
            assertEquals(listOf(NetworkAction.LIST), actions)
            assertEquals(1, repo.refreshCategoriesCalls)
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

        assertEquals(1, repo.refreshCategoriesCalls)
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
            val gate = CompletableDeferred<ForumResult<List<Category>>>()
            repo.suspendRefreshCategoriesUntil = gate

            vm.refresh()

            assertEquals(true, awaitItem())

            // Release the gate → finally branch flips the flag back off.
            gate.complete(ForumResult.Success(SAMPLE_CATEGORIES))
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
        private val categories: MutableSharedFlow<ForumCategoriesUpdate> =
            MutableSharedFlow(replay = 1, extraBufferCapacity = 4)
        var refreshCategoriesCalls = 0
            private set
        var suspendRefreshCategoriesUntil: CompletableDeferred<ForumResult<List<Category>>>? = null

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            categories.map { it.result }

        override fun observeCategoryUpdates(): Flow<ForumCategoriesUpdate> =
            categories.asSharedFlow()

        override suspend fun getCategories(forceRefreshIfStale: Boolean): ForumResult<List<Category>> =
            categories.replayCache.lastOrNull { it.result !is ForumResult.Loading }?.result
                ?: ForumResult.Success(emptyList())

        override suspend fun refreshCategories() {
            refreshCategoriesCalls++
            suspendRefreshCategoriesUntil?.await()?.let { emitCategories(it, isRefresh = true) }
        }

        override fun observeSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>> =
            MutableSharedFlow<ForumResult<List<SubCategory>>>(replay = 1).asSharedFlow()

        override fun observeCachedSubcategories(cat: Int): Flow<ForumResult<List<SubCategory>>?> =
            MutableSharedFlow<ForumResult<List<SubCategory>>?>(replay = 1).asSharedFlow()

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

        suspend fun emitCategories(result: ForumResult<List<Category>>, isRefresh: Boolean = false) {
            categories.emit(ForumCategoriesUpdate(result, isRefresh))
        }
    }
}
