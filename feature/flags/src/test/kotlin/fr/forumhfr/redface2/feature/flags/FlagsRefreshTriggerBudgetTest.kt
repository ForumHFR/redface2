package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.flags.FlagLookup
import fr.forumhfr.redface2.core.domain.flags.FlagPullBatch
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Repository calls per action. Each row after startup excludes the setup calls.
 * On a first visit, the cold observation is allowed to settle before the route's landing effect.
 *
 * | Scenario | Explicit refresh types before → after | Cold observe fetch types before → after |
 * | --- | --- | --- |
 * | Cold start on CYAN (landing + resume) | CYAN → CYAN | CYAN → CYAN |
 * | First RED visit, then first FAVORITE visit | RED, FAVORITE → RED, FAVORITE | RED, FAVORITE → RED, FAVORITE |
 * | Return from a CYAN topic within the window | CYAN → CYAN | 0 → 0 |
 * | Return from a Super topic | 0 → 0 | 0 → 0 |
 * | Switch back to CYAN after that Super return, still within the window | CYAN → CYAN | 0 → 0 |
 * | Resume after 10 s / 20 s | 0 / CYAN → 0 / CYAN | 0 / 0 → 0 / 0 |
 * | CYAN pull | CYAN → CYAN | 0 → 0 |
 * | Super pull | CYAN, RED, FAVORITE → same types in one sweep batch (120 → 80 REST calls) | 0 → 0 |
 * | Two completed CYAN pulls 5 s apart | CYAN, CYAN → CYAN, CYAN | 0 → 0 |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlagsRefreshTriggerBudgetTest {
    @Test
    fun `cold CYAN start observes and refreshes once each even with the resume effect`() = runTest {
        scenario {
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 0)

            landAndResume()

            assertCalls(refreshes = listOf(FlagType.CYAN), observations = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN)
        }
    }

    @Test
    fun `first RED and FAVORITE visits both observe and explicitly refresh inside the CYAN window`() = runTest {
        scenario {
            startOnCyan()

            visit(FlagTab.Red)
            assertCalls(refreshes = listOf(FlagType.RED), observations = listOf(FlagType.RED))
            assertPinned(FlagTab.Red, FlagType.RED)

            visit(FlagTab.Favorite)
            assertCalls(refreshes = listOf(FlagType.FAVORITE), observations = listOf(FlagType.FAVORITE))
            assertPinned(FlagTab.Favorite, FlagType.FAVORITE)
        }
    }

    @Test
    fun `returning from a CYAN topic bypasses the throttle once without a cold observe`() = runTest {
        scenario {
            startOnCyan()

            returnFromTopic()

            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `returning from a Super topic sends no refresh and leaves the bypass pending for CYAN`() = runTest {
        scenario {
            startOnCyan()
            visit(FlagTab.Super)
            assertCalls()
            assertPinned(FlagTab.Super, FlagType.CYAN)

            returnFromTopic()

            assertCalls()
            assertPinned(FlagTab.Super, FlagType.CYAN)

            visit(FlagTab.Cyan)
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `resume after ten seconds stays inside the window and resume after twenty seconds refreshes`() = runTest {
        scenario {
            startOnCyan()

            resumeAfter(10_000)
            assertCalls()
            assertPinned(FlagTab.Cyan, FlagType.CYAN)

            // The throttled resume did not re-arm the window. This is t = 20 s from landing.
            resumeAfter(10_000)
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `CYAN pull refreshes only CYAN inside the automatic window`() = runTest {
        scenario {
            startOnCyan()

            pull()

            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `Super pull refreshes the three backing types in order without observing them`() = runTest {
        scenario {
            startOnCyan()
            visit(FlagTab.Super)
            assertCalls()

            pull()

            assertCalls(refreshes = listOf(FlagType.CYAN, FlagType.RED, FlagType.FAVORITE), sharedSweep = true)
            assertPinned(FlagTab.Super, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `completed pulls five seconds apart each refresh CYAN`() = runTest {
        scenario {
            startOnCyan()

            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)

            advanceTimeBy(5_000)
            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 3)
        }
    }

    private fun TestScope.scenario(block: Scenario.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val flags = RecordingFlagRepository()
            val vm = viewModel(flags)
            try {
                runCurrent()
                Scenario(this, vm, flags).block()
            } finally {
                vm.viewModelScope.cancel()
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.viewModel(flags: FlagRepository): FlagsViewModel {
        val categories = ForumResult.Success(listOf(
            Category(id = CATEGORY_ID, name = "IA", forceSubcat = false, subcategoryCount = 0),
        ))
        val forum = mockk<ForumRepository> {
            every { observeCategories() } returns flowOf(categories)
            every { observeCachedCategories() } returns flowOf(categories)
        }
        val preferences = mockk<UserPreferencesRepository> {
            every { observeShowDtSection() } returns flowOf(false)
            every { observeFunnyEmptyState() } returns flowOf(false)
            every { observeAvatarAppearance() } returns flowOf(AvatarAppearance())
            every { observeFlagsAutoRefresh() } returns flowOf(true)
            every { observeFlagsGroupByCategory() } returns flowOf(false)
            every { observeFlagsHideReadCategories() } returns flowOf(false)
            every { observeFlagsPerTabOverride() } returns flowOf(false)
            every { observeFlagsViewSettings(any()) } answers {
                flowOf(FlagsViewSettings(groupByCategory = false, unreadOnly = firstArg<FlagType>() == FlagType.CYAN))
            }
        }
        val superFavorites = mockk<SuperFavoriteRepository> {
            every { observeSuperFavoriteTopics() } returns flowOf(setOf(
                SuperFavoriteTopic(cat = CATEGORY_ID, topicId = PINNED_TOPIC_ID, title = "Pinned topic", subcat = null),
            ))
        }
        return FlagsViewModel(
            authRepository = mockk<AuthRepository> {
                every { observeAuthState() } returns flowOf(AuthState.Authenticated("XaT"))
            },
            flagRepository = flags,
            forumRepository = forum,
            userPreferencesRepository = preferences,
            superFavoriteRepository = superFavorites,
            superFavoriteListMapper = SuperFavoriteListMapper(flags, forum, preferences),
            messagesRepository = mockk(),
            mpStorageRepository = mockk(),
            clock = SchedulerClock(testScheduler),
            externalScope = backgroundScope,
            refreshDiagnostics = FlagRefreshDiagnostics(DiagnosticsLog()),
        )
    }

    private class Scenario(
        private val scope: TestScope,
        private val vm: FlagsViewModel,
        private val flags: RecordingFlagRepository,
    ) {
        fun startOnCyan() {
            landAndResume()
            assertCalls(refreshes = listOf(FlagType.CYAN), observations = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN)
        }

        fun landAndResume() {
            vm.maybeAutoRefresh()
            vm.maybeAutoRefresh(FlagRefreshTrigger.RESUME)
            scope.runCurrent()
        }

        fun visit(tab: FlagTab) {
            vm.selectTab(tab)
            scope.runCurrent()
            vm.maybeAutoRefresh()
            scope.runCurrent()
        }

        fun returnFromTopic() {
            vm.onFlagOpened()
            scope.advanceTimeBy(5_000)
            landAndResume()
        }

        fun resumeAfter(millis: Long) {
            scope.advanceTimeBy(millis)
            vm.maybeAutoRefresh(FlagRefreshTrigger.RESUME)
            scope.runCurrent()
        }

        fun pull() {
            vm.refresh()
            scope.runCurrent()
        }

        fun assertCalls(
            refreshes: List<FlagType> = emptyList(),
            observations: List<FlagType> = emptyList(),
            sharedSweep: Boolean = false,
        ) {
            assertEquals("explicit refreshes", refreshes, flags.refreshes.toList())
            assertEquals("cold observation fetches", observations, flags.initialObserves.toList())
            if (sharedSweep) assertNotNull(flags.pullBatches.distinct().single())
            else assertEquals(List(refreshes.size) { null }, flags.pullBatches)
            flags.pullBatches.clear()
            flags.refreshes.clear()
            flags.initialObserves.clear()
        }

        fun assertPinned(tab: FlagTab, type: FlagType, explicitRefreshes: Int = 1) {
            val tabState = vm.flagsTabState.value
            assertEquals(tab, tabState.tab)
            val state = tabState.flagsState as FlagsListUiState.Success
            assertEquals(FlagRefreshStatus.COMPLETE, state.refreshStatus)
            val row = (state.content as FlagsContent.Flat).rows.single().flag
            assertEquals(pinnedFlag(type, INITIAL_ANCHOR + explicitRefreshes), row)
            assertFalse(vm.isRefreshing.value)
            assertNull(vm.refreshWarning.value)
        }
    }

    private class RecordingFlagRepository : FlagRepository {
        private val snapshots = FlagType.entries.associateWith { MutableStateFlow<FlagsResult.Success?>(null) }
        private val cacheUpdates = MutableSharedFlow<FlagType>(extraBufferCapacity = FlagType.entries.size)
        val refreshes = mutableListOf<FlagType>()
        val initialObserves = mutableListOf<FlagType>()
        val pullBatches = mutableListOf<FlagPullBatch?>()
        private var isRefreshing = false

        override fun observe(type: FlagType): Flow<FlagsResult> = flow {
            val snapshot = snapshots.getValue(type)
            if (snapshot.value == null) {
                initialObserves += type
                emit(FlagsResult.Loading)
                publish(pinnedFlag(type, INITIAL_ANCHOR))
            }
            emitAll(snapshot.filterNotNull())
        }

        override suspend fun refresh(type: FlagType): FlagsResult {
            check(!isRefreshing) { "Expected sequential refreshes" }
            isRefreshing = true
            try {
                refreshes += type
                pullBatches += currentCoroutineContext()[FlagPullBatch]
                yield()
                val previousAnchor = snapshots.getValue(type).value?.flags?.single()?.lastPostReadId
                return publish(pinnedFlag(type, (previousAnchor ?: INITIAL_ANCHOR) + 1))
            } finally {
                isRefreshing = false
            }
        }

        private suspend fun publish(flag: Flag): FlagsResult.Success =
            FlagsResult.Success(listOf(flag)).also {
                snapshots.getValue(flag.type).value = it
                cacheUpdates.emit(flag.type)
            }

        override fun observeCacheUpdates(types: Set<FlagType>): Flow<FlagType> = cacheUpdates.filter { it in types }

        override fun clearSessionCache() {
            snapshots.values.forEach { it.value = null }
        }

        override suspend fun findCachedFlag(cat: Int, topicId: Int): Flag? =
            snapshots.values.asSequence().flatMap { it.value?.flags.orEmpty().asSequence() }
                .firstOrNull { it.cat == cat && it.topicId == topicId }

        override suspend fun addFlag(context: FlagAddContext): Result<Unit> = error("Unexpected addFlag")
        override suspend fun removeFlag(flag: Flag): Result<Unit> = error("Unexpected removeFlag")
        override suspend fun resolveFavorite(cat: Int, topicId: Int): Result<Boolean> =
            error("Unexpected resolveFavorite")
        override suspend fun findFlag(cat: Int, topicId: Int): FlagLookup = error("Unexpected findFlag")
    }

    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler,
        private val zone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun instant(): Instant = Instant.EPOCH.plusMillis(scheduler.currentTime)
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = SchedulerClock(scheduler, zone)
    }

    private companion object {
        const val CATEGORY_ID = 32
        const val PINNED_TOPIC_ID = 1
        const val INITIAL_ANCHOR = 40_563L

        fun pinnedFlag(type: FlagType, lastPostReadId: Long) = Flag(
            cat = CATEGORY_ID,
            subcat = null,
            topicId = PINNED_TOPIC_ID,
            title = "Pinned topic",
            totalPages = 5,
            replyCount = 172,
            type = type,
            isFavorite = type == FlagType.FAVORITE,
            hasUnread = type == FlagType.CYAN,
            lastReadPage = 4,
            lastPostReadId = lastPostReadId,
            firstPostAuthor = "",
            lastReplyAuthor = "",
            lastReplyAt = "",
        )
    }
}
