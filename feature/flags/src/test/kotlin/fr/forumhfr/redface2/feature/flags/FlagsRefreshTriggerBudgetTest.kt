package fr.forumhfr.redface2.feature.flags

import androidx.lifecycle.viewModelScope
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshDiagnostics
import fr.forumhfr.redface2.core.domain.diagnostics.FlagRefreshTrigger
import fr.forumhfr.redface2.core.domain.error.HfrRestAvailability
import fr.forumhfr.redface2.core.domain.flags.FlagCategoryCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagCoverage
import fr.forumhfr.redface2.core.domain.flags.FlagLookup
import fr.forumhfr.redface2.core.domain.flags.FlagPullBatch
import fr.forumhfr.redface2.core.domain.flags.FlagReconciliationResult
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshClock
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshStatus
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagRefreshIssue
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.flags.TopicRead
import fr.forumhfr.redface2.core.domain.flags.TopicReadQueue
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.AvatarAppearance
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteRepository
import fr.forumhfr.redface2.core.domain.preferences.SuperFavoriteTopic
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.stats.NetworkAction
import fr.forumhfr.redface2.core.domain.stats.NetworkActionRecorder
import fr.forumhfr.redface2.core.domain.stats.NetworkTrigger
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.FlagSource
import fr.forumhfr.redface2.core.model.write.FlagAddContext
import io.mockk.every
import io.mockk.mockk
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repository calls per action. Each row after startup excludes the setup calls.
 * On a first visit, the cold observation is allowed to settle before the route's landing effect.
 *
 * | Scenario | Explicit refresh types before → after | Cold observe fetch types before → after |
 * | --- | --- | --- |
 * | Cold start on CYAN (landing + resume) | CYAN → CYAN | CYAN → CYAN |
 * | First RED visit, then first FAVORITE visit | RED, FAVORITE → RED, FAVORITE | RED, FAVORITE → RED, FAVORITE |
 * | Return from a CYAN topic within / past the window | CYAN / CYAN → targeted / CYAN | 0 → 0 |
 * | Return from a topic opened outside Flags after 30 s | CYAN → targeted | 0 → 0 |
 * | Two CYAN topic returns inside the window | CYAN twice → targeted twice | 0 → 0 |
 * | Return on Super, then switch to CYAN | 0 + CYAN → 0 + targeted | 0 → 0 |
 * | Resume after 10 s / 20 s | 0 / CYAN → 0 / 0 | 0 → 0 |
 * | Resume at 30 s / 90 s / 130 s | CYAN / CYAN / CYAN → 0 / 0 / CYAN | 0 → 0 |
 * | CYAN pull | CYAN → CYAN | 0 → 0 |
 * | Super pull | CYAN, RED, FAVORITE → same types in one sweep batch (120 → 80 REST calls) | 0 → 0 |
 * | Super pull joining in-flight CYAN | CYAN, RED, FAVORITE → same types in one sweep batch (120 → 80) | 0 → 0 |
 * | Two CYAN pulls 5 s apart | CYAN, CYAN → CYAN | 0 → 0 |
 * | Banner or snackbar Retry inside the floor after an incomplete refresh | CYAN → 0 | 0 → 0 |
 * | Super pull inside the floor of one of its types | CYAN, RED, FAVORITE → 0 | 0 → 0 |
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
    fun `returning twice within the window reconciles twice without a full refresh or a cold observe`() = runTest {
        scenario {
            startOnCyan()

            repeat(2) { index ->
                returnFromTopic()
                assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
                assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2 + index, read = true)
            }
        }
    }

    @Test
    fun `a topic opened outside Flags still reconciles on landing`() = runTest {
        scenario {
            startOnCyan()
            returnFromTopic(afterMillis = 30_000)
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2, read = true)
        }
    }

    @Test
    fun `returning after the window runs only the full fetch and drains the topic reads`() = runTest {
        scenario {
            startOnCyan()
            returnFromTopic(afterMillis = 130_000)
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
            landAndResume()
            assertCalls()
        }
    }

    @Test
    fun `Super stays at zero automatic calls with a full queue and retains the reads for Cyan`() = runTest {
        scenario {
            startOnCyan()
            visit(FlagTab.Super)
            assertCalls()

            fillQueue()
            returnFromTopic()
            assertCalls()
            assertPinned(FlagTab.Super, FlagType.CYAN, explicitRefreshes = 1)

            visit(FlagTab.Cyan)
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2, read = true)
        }
    }

    @Test
    fun `failed reconciliation prevents the floor from claiming the previous refresh succeeded`() = runTest {
        scenario {
            startOnCyan()
            flags.reconciliationFails = true
            returnFromTopic()
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
            assertPinned(FlagTab.Cyan, FlagType.CYAN)

            pull(expectAdmission = false)
            assertCalls()
            assertTrue(feedback.single() is ManualRefreshFeedback.RetryAt)
        }
    }

    @Test
    fun `an expired Cyan window covers Cyan but leaves the same read for the Favorite window`() = runTest {
        scenario {
            startOnCyan()
            advance(60_000)
            visit(FlagTab.Favorite)
            assertCalls(refreshes = listOf(FlagType.FAVORITE), observations = listOf(FlagType.FAVORITE))
            visit(FlagTab.Cyan)
            assertCalls()
            returnFromTopic(afterMillis = 61_000)
            assertCalls(refreshes = listOf(FlagType.CYAN))
            visit(FlagTab.Favorite)
            assertCalls(reconciles = listOf(listOf(FlagType.FAVORITE)))
            assertPinned(FlagTab.Favorite, FlagType.FAVORITE, explicitRefreshes = 2, read = true)
        }
    }

    @Test
    fun `a pull adopting a reconciliation keeps a newer read of the same topic pending`() = runTest {
        scenario {
            startOnCyan()
            advance(35_000)
            val release = holdNextReconciliation()
            returnFromTopic(afterMillis = 0)
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))

            recordTopicRead()
            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN))
            release()

            landAndResume()
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 4, read = true)
        }
    }

    @Test
    fun `resumes at ten and twenty seconds both stay inside the window`() = runTest {
        scenario {
            startOnCyan()
            repeat(2) {
                resumeAfter(10_000)
                assertCalls()
                assertPinned(FlagTab.Cyan, FlagType.CYAN)
            }
        }
    }

    @Test
    fun `resumes at thirty and ninety seconds are ignored while the one at 130 seconds refreshes`() = runTest {
        scenario {
            startOnCyan()

            val ignoredBeforeResume = ignored(NetworkTrigger.RESUME)
            resumeAfter(30_000)
            assertCalls()
            resumeAfter(60_000)
            assertCalls()
            resumeAfter(40_000)
            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
            assertEquals(2, ignored(NetworkTrigger.RESUME) - ignoredBeforeResume)
        }
    }

    @Test
    fun `CYAN pull refreshes only CYAN inside the automatic window`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)

            pull()

            assertCalls(refreshes = listOf(FlagType.CYAN))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `Super pull refreshes the three backing types in order without observing them`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)
            visit(FlagTab.Super)
            assertCalls()

            pull()

            assertCalls(refreshes = listOf(FlagType.CYAN, FlagType.RED, FlagType.FAVORITE), sharedSweep = true)
            assertPinned(FlagTab.Super, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `Super pull shares the sticky sweep of a Cyan refresh already in flight`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)
            val finishCyan = holdNextRefresh()
            pull()

            visit(FlagTab.Super)
            pull()
            finishCyan()

            assertCalls(refreshes = listOf(FlagType.CYAN, FlagType.RED, FlagType.FAVORITE), sharedSweep = true)
            assertPinned(FlagTab.Super, FlagType.CYAN, explicitRefreshes = 2)
        }
    }

    @Test
    fun `a second pull five seconds after completion sends nothing and does not arm the spinner`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)
            val finish = holdNextRefresh()
            pull()
            advance(35_000)
            finish()
            assertCalls(refreshes = listOf(FlagType.CYAN))

            advance(5_000)
            pull(expectAdmission = false)
            assertCalls()
            assertEquals(listOf(ManualRefreshFeedback.Updated(5)), feedback)
            assertEquals(1, ignored(NetworkTrigger.PULL))
            assertPinned(FlagTab.Cyan, FlagType.CYAN, explicitRefreshes = 2)

            advance(25_000)
            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN))
        }
    }

    @Test
    fun `banner and snackbar Retry obey the same floor after a failed refresh`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)
            flags.incomplete = true
            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN))
            advance(5_000)

            retryWarning()
            pull(expectAdmission = false)
            assertCalls()
            val retry = ManualRefreshFeedback.RetryAt(Instant.EPOCH.plusSeconds(60))
            assertEquals(listOf(retry, retry), feedback)
            assertEquals(2, ignored(NetworkTrigger.PULL))
        }
    }

    @Test
    fun `Super is refused as a whole when any backing type is inside the floor`() = runTest {
        scenario {
            startOnCyan()
            advance(5_000)
            visit(FlagTab.Super)
            pull(expectAdmission = false)

            assertCalls()
            assertEquals(3, ignored(NetworkTrigger.PULL))
            assertEquals(listOf(ManualRefreshFeedback.Updated(5)), feedback)
            assertPinned(FlagTab.Super, FlagType.CYAN)

            advance(25_000)
            pull()
            assertCalls(refreshes = FlagType.entries, sharedSweep = true)
            advance(5_000)
            pull(expectAdmission = false)
            assertCalls()
        }
    }

    @Test
    fun `a blocked pull arms the floor and the pause also suppresses reconciliation`() = runTest {
        scenario {
            startOnCyan()
            advance(30_000)
            val deadline = Instant.EPOCH.plusSeconds(45)
            blockedUntil.value = deadline
            settle()
            pull(expectAdmission = false)
            assertCalls()
            assertEquals(listOf(ManualRefreshFeedback.RetryAt(Instant.EPOCH.plusSeconds(60))), feedback)

            val ignoredBefore = ignored(NetworkTrigger.RESUME)
            returnFromTopic()
            assertCalls()
            assertEquals(ignoredBefore + 1, ignored(NetworkTrigger.RESUME))

            advance(10_000)
            blockedUntil.value = null
            settle()
            advance(5_000)
            pull(expectAdmission = false)
            assertCalls()
            assertEquals(List(2) { ManualRefreshFeedback.RetryAt(Instant.EPOCH.plusSeconds(60)) }, feedback)
            landAndResume()
            assertCalls(reconciles = listOf(listOf(FlagType.CYAN)))
        }
    }

    @Test
    fun `changing account clears both refresh windows`() = runTest {
        scenario {
            startOnCyan()
            advance(5_000)
            auth.value = AuthState.Authenticated("Other")
            settle()
            landAndResume()
            assertCalls(refreshes = listOf(FlagType.CYAN), observations = listOf(FlagType.CYAN))

            auth.value = AuthState.Authenticated("Third")
            settle()
            pull()
            assertCalls(refreshes = listOf(FlagType.CYAN), observations = listOf(FlagType.CYAN))
        }
    }

    private fun TestScope.scenario(block: Scenario.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val flags = RecordingFlagRepository()
            val topicReads = TopicReadQueue()
            val blockedUntil = MutableStateFlow<Instant?>(null)
            val auth = MutableStateFlow<AuthState>(AuthState.Authenticated("XaT"))
            val ignored = mutableMapOf<NetworkTrigger, Int>()
            val actions = NetworkActionRecorder { action, trigger, count ->
                if (action == NetworkAction.FLAG_REFRESH_IGNORED) {
                    ignored[trigger] = ignored.getOrDefault(trigger, 0) + count.toInt()
                }
            }
            val vm = viewModel(flags, topicReads, auth, blockedUntil, actions)
            try {
                val scenario = Scenario(this, vm, flags, topicReads, auth, blockedUntil, ignored)
                vm.viewModelScope.launch { vm.manualRefreshFeedback.collect { scenario.feedback += it } }
                vm.viewModelScope.launch { vm.restBlockedUntil.collect() }
                runCurrent()
                scenario.block()
            } finally {
                vm.viewModelScope.cancel()
                runCurrent()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.viewModel(
        flags: FlagRepository,
        topicReads: TopicReadQueue,
        auth: MutableStateFlow<AuthState>,
        blockedUntil: MutableStateFlow<Instant?>,
        actions: NetworkActionRecorder,
    ): FlagsViewModel {
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
                flowOf(FlagsViewSettings(groupByCategory = false, unreadOnly = false))
            }
        }
        val superFavorites = mockk<SuperFavoriteRepository> {
            every { observeSuperFavoriteTopics() } returns flowOf(setOf(
                SuperFavoriteTopic(cat = CATEGORY_ID, topicId = PINNED_TOPIC_ID, title = "Pinned topic", subcat = null),
            ))
        }
        return FlagsViewModel(
            authRepository = mockk<AuthRepository> {
                every { observeAuthState() } returns auth
            },
            flagRepository = flags,
            forumRepository = forum,
            userPreferencesRepository = preferences,
            superFavoriteRepository = superFavorites,
            superFavoriteListMapper = SuperFavoriteListMapper(flags, forum, preferences),
            messagesRepository = mockk(),
            mpStorageRepository = mockk(),
            clock = SchedulerClock(testScheduler),
            refreshClock = mockk<FlagRefreshClock> {
                every { nowNanos() } answers { testScheduler.currentTime * 1_000_000L }
            },
            externalScope = backgroundScope,
            refreshDiagnostics = FlagRefreshDiagnostics(DiagnosticsLog(), networkActions = actions),
            topicReads = topicReads,
            restAvailability = object : HfrRestAvailability by HfrRestAvailability.Available {
                override val blockedUntil: Flow<Instant?> = blockedUntil
                override suspend fun cooldownDeadline(): Instant? = blockedUntil.value
            },
        )
    }

    @Suppress("LongParameterList") // Each input is shared with the scenario's ViewModel or collection scope.
    private class Scenario(
        private val scope: TestScope,
        private val vm: FlagsViewModel,
        val flags: RecordingFlagRepository,
        private val topicReads: TopicReadQueue,
        val auth: MutableStateFlow<AuthState>,
        val blockedUntil: MutableStateFlow<Instant?>,
        private val ignored: Map<NetworkTrigger, Int>,
    ) {
        val feedback = mutableListOf<ManualRefreshFeedback>()

        fun ignored(trigger: NetworkTrigger): Int = ignored.getOrDefault(trigger, 0)

        fun advance(millis: Long) = scope.advanceTimeBy(millis)

        fun settle() = scope.runCurrent()

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

        fun fillQueue() {
            repeat(64) { topicReads.record("XaT", TopicRead(CATEGORY_ID, -it - 1)) }
        }

        fun recordTopicRead() = topicReads.record("XaT", TopicRead(CATEGORY_ID, PINNED_TOPIC_ID))

        fun returnFromTopic(afterMillis: Long = 5_000) {
            recordTopicRead()
            advance(afterMillis)
            landAndResume()
        }

        fun resumeAfter(millis: Long) {
            scope.advanceTimeBy(millis)
            vm.maybeAutoRefresh(FlagRefreshTrigger.RESUME)
            scope.runCurrent()
        }

        fun pull(expectAdmission: Boolean = true) {
            var admitted = false
            vm.pullRefresh { admitted = true }
            assertEquals(expectAdmission, admitted)
            scope.runCurrent()
            if (!expectAdmission) assertFalse(vm.isRefreshing.value)
        }

        fun retryWarning() {
            vm.retryRefreshWarning(requireNotNull(vm.refreshWarning.value))
            scope.runCurrent()
        }

        fun holdNextReconciliation(): () -> Unit {
            val gate = CompletableDeferred<Unit>()
            flags.reconciliationGate = gate
            return {
                gate.complete(Unit)
                scope.runCurrent()
            }
        }

        fun holdNextRefresh(): () -> Unit {
            val gate = CompletableDeferred<Unit>()
            flags.refreshGate = gate
            return {
                gate.complete(Unit)
                scope.runCurrent()
            }
        }

        fun assertCalls(
            refreshes: List<FlagType> = emptyList(),
            observations: List<FlagType> = emptyList(),
            sharedSweep: Boolean = false,
            reconciles: List<List<FlagType>> = emptyList(),
        ) {
            assertEquals("targeted reconciliations", reconciles, flags.reconciles.toList())
            flags.reconciles.clear()
            assertEquals("explicit refreshes", refreshes, flags.refreshes.toList())
            assertEquals("cold observation fetches", observations, flags.initialObserves.toList())
            val sweepBatches = flags.pullBatches.map(::requireNotNull)
            assertEquals("sticky sweep batches", if (sharedSweep) 1 else refreshes.size, sweepBatches.distinct().size)
            flags.pullBatches.clear()
            flags.refreshes.clear()
            flags.initialObserves.clear()
        }

        fun assertPinned(tab: FlagTab, type: FlagType, explicitRefreshes: Int = 1, read: Boolean = false) {
            val tabState = vm.flagsTabState.value
            assertEquals(tab, tabState.tab)
            val state = tabState.flagsState as FlagsListUiState.Success
            assertEquals(FlagRefreshStatus.COMPLETE, state.refreshStatus)
            val row = (state.content as FlagsContent.Flat).rows.single().flag
            val expected = pinnedFlag(type, INITIAL_ANCHOR + explicitRefreshes)
            assertEquals(if (read) expected.copy(hasUnread = false) else expected, row)
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
        val reconciles = mutableListOf<List<FlagType>>()
        var reconciliationFails = false
        var reconciliationGate: CompletableDeferred<Unit>? = null
        var incomplete = false
        var refreshGate: CompletableDeferred<Unit>? = null
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
                val gate = refreshGate
                refreshGate = null
                gate?.await()
                yield()
                val previousAnchor = snapshots.getValue(type).value?.flags?.single()?.lastPostReadId
                val issue = if (incomplete) FlagRefreshIssue(paginationTruncated = true) else null
                return publish(pinnedFlag(type, (previousAnchor ?: INITIAL_ANCHOR) + 1), issue)
            } finally {
                isRefreshing = false
            }
        }

        override suspend fun reconcileTopicReads(
            reads: Map<TopicRead, Set<FlagType>>,
            visibleType: FlagType?,
            maxRequests: Int,
        ): FlagReconciliationResult {
            val read = TopicRead(CATEGORY_ID, PINNED_TOPIC_ID)
            val type = requireNotNull(visibleType)
            assertTrue(type in reads.getValue(read))
            reconciles += listOf(type)
            val gate = reconciliationGate
            reconciliationGate = null
            gate?.await()
            if (reconciliationFails) return FlagReconciliationResult(failedTypes = setOf(type))
            val flag = snapshots.getValue(type).value?.flags?.singleOrNull()
            return if (flag == null) {
                FlagReconciliationResult()
            } else {
                publish(flag.copy(hasUnread = false, lastPostReadId = requireNotNull(flag.lastPostReadId) + 1))
                FlagReconciliationResult(covered = mapOf(read to setOf(type)))
            }
        }

        private suspend fun publish(flag: Flag, issue: FlagRefreshIssue? = null): FlagsResult.Success =
            FlagsResult.Success(listOf(flag), issue, coverage = FlagCoverage(
                listOf(FlagSource.BUCKET, FlagSource.STICKY).map {
                    FlagCategoryCoverage(flag.cat, it, Instant.EPOCH, Instant.EPOCH)
                },
            )).also {
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
