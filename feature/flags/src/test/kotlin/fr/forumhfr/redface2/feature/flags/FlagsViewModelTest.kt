package fr.forumhfr.redface2.feature.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
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
class FlagsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `flagsState stays null while user is anonymous`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Anonymous, flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // Anonymous must NOT subscribe to categories (no spurious public fetch, cf. §5).
        assertEquals(0, forum.observeCategoriesSubscriptions)
    }

    @Test
    fun `flagsState mirrors the current tab when authenticated`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            // Initial value (null) before stateIn fires.
            awaitItem()
            // FakeFlagRepository emits Loading then Success(emptyList) on subscribe.
            flags.emit(FlagType.CYAN, FlagsResult.Loading)
            assertEquals(FlagsListUiState.Loading, awaitItem())
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            val success = awaitItem() as FlagsListUiState.Success
            assertEquals(1, flatTopics(success).single().topicId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flagsState keeps the list during a refresh instead of blanking to Loading (#225)`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null before stateIn fires
            // Cold load: the first Loading passes through so the screen shows its initial spinner.
            flags.emit(FlagType.CYAN, FlagsResult.Loading)
            assertEquals(FlagsListUiState.Loading, awaitItem())
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            assertEquals(1, flatTopics(awaitItem() as FlagsListUiState.Success).single().topicId)

            // A swipe refresh re-broadcasts Loading: it must be SUPPRESSED so the list stays
            // anchored under the PullToRefreshBox indicator (no second centered spinner, #225).
            flags.emit(FlagType.CYAN, FlagsResult.Loading)
            // The next *visible* state is the refreshed Success — the intermediate Loading
            // never surfaces (otherwise awaitItem() here would return Loading and fail the cast).
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(2, FlagType.CYAN))))
            assertEquals(2, flatTopics(awaitItem() as FlagsListUiState.Success).single().topicId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectTab switches the flagsState source`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            val cyan = awaitItem() as FlagsListUiState.Success
            assertEquals(FlagType.CYAN, flatTopics(cyan).single().type)

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED))))
            val red = awaitItem() as FlagsListUiState.Success
            assertEquals(FlagType.RED, flatTopics(red).single().type)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refresh forwards to the repository for the current tab`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.selectTab(FlagTab.Favorite)
        vm.refresh()

        assertEquals(listOf(FlagType.FAVORITE), flags.refreshCalls)
        // The catalogue is refreshed by the read path, never by the flags screen (cf. §5).
        assertEquals(0, forum.refreshCategoriesCalls)
    }

    @Test
    fun `refresh toggles isRefreshing around the round-trip`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        assertEquals(false, vm.isRefreshing.value)
        vm.refresh()
        // FakeFlagRepository.refresh returns immediately, so by the time the launched
        // coroutine settles isRefreshing is back to false (UnconfinedTestDispatcher runs
        // it eagerly). The contract pinned here: it must end at false, never stuck true.
        assertEquals(false, vm.isRefreshing.value)
        assertEquals(listOf(FlagType.CYAN), flags.refreshCalls)
        assertEquals("pull-to-refresh must never refresh the categories catalogue", 0, forum.refreshCategoriesCalls)
    }

    @Test
    fun `selecting the Super tab is a placeholder with no fetch and null state`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            awaitItem() // CYAN content
            val subscriptionsWhileOnCyan = forum.observeCategoriesSubscriptions

            vm.selectTab(FlagTab.Super)
            // Super maps to no FlagType: the state collapses back to null (placeholder body).
            assertNull(awaitItem())

            // Super must not start a new categories observation either.
            assertEquals(subscriptionsWhileOnCyan, forum.observeCategoriesSubscriptions)
            cancelAndIgnoreRemainingEvents()
        }

        // No FlagType is backing Super, so refresh() while on it must not hit the repository.
        vm.refresh()
        assertTrue("Super refresh must be a no-op", flags.refreshCalls.isEmpty())
        assertEquals(false, vm.isRefreshing.value)
    }

    @Test
    fun `re-tapping the already selected Cyan tab toggles the read participated filter`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        // Cyan is selected by default; re-tapping it flips the toggle on, then off.
        assertEquals(false, vm.showReadParticipatedTopics.value)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(true, vm.showReadParticipatedTopics.value)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(false, vm.showReadParticipatedTopics.value)
        // Re-tap must not switch the selected tab or trigger a refetch.
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertTrue("re-tap must not refetch", flags.refreshCalls.isEmpty())
    }

    @Test
    fun `selecting Cyan from another tab does not toggle the filter`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.selectTab(FlagTab.Red)
        assertEquals(false, vm.showReadParticipatedTopics.value)

        // First tap on Cyan from RED selects it without toggling the filter.
        vm.selectTab(FlagTab.Cyan)
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertEquals(false, vm.showReadParticipatedTopics.value)
    }

    // Round-2 review (PR #207): the `logout clears the private flags cache before resetting
    // auth state` test moved to `AppAccountViewModelTest`. `FlagsViewModel.logout()` is gone —
    // the global account menu (#198) now drives the logout from `AppAccountViewModel` which
    // owns the canonical `clearSessionCache → authRepository.logout` ordering. The fakes
    // below are kept because the rest of the suite still exercises auth-state transitions
    // through `clearFlagsCacheIfSessionChanged`.

    @Test
    fun `flagsState propagates SessionExpiredException cause to drive the reconnect CTA`() = runTest {
        // FlagsRoute renders the reconnect CTA branch when `current.cause is SessionExpiredException`.
        // A future refactor that drops the `cause` field on FlagsListUiState.Failure (e.g. flattening
        // it to a `String message`) would silently break that detection. This test pins the
        // contract: the SessionExpiredException must traverse the repository → ViewModel →
        // exposed state without being unwrapped.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Failure(expired))
            val failure = awaitItem() as FlagsListUiState.Failure
            assertTrue(
                "expected SessionExpiredException to traverse the stack — got ${failure.cause::class.simpleName}",
                failure.cause is SessionExpiredException,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CYAN tab hides read participated topics by default before grouping`() = runTest {
        // #154: « Mes sujets » should not pollute the actionable view with topics the user
        // already finished reading. The filter is applied at the ViewModel layer (not in
        // the repository) so toggling the preference reactively re-emits the filtered list
        // without re-fetching. #179: the filter happens BEFORE the category grouping, so a
        // category whose every cyan is read becomes an empty section.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true, cat = 1),
                        stubFlag(2, FlagType.CYAN, hasUnread = false, cat = 1),
                        stubFlag(3, FlagType.CYAN, hasUnread = true, cat = 10),
                    ),
                ),
            )

            val filtered = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "expected only hasUnread=true topics under default CYAN filter",
                listOf(1, 3),
                flatTopics(filtered).map { it.topicId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setShowReadParticipatedTopics true reveals read CYAN topics without refetch`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null

            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true),
                        stubFlag(2, FlagType.CYAN, hasUnread = false),
                    ),
                ),
            )
            assertEquals(listOf(1), flatTopics(awaitItem() as FlagsListUiState.Success).map { it.topicId })

            vm.setShowReadParticipatedTopics(true)
            // No new refresh() call — the toggle alone must re-emit the unfiltered list
            // because flagsState combines the source flow with showReadParticipatedTopics.
            val full = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1, 2), flatTopics(full).map { it.topicId })
            assertTrue("toggle must not trigger a network refresh", flags.refreshCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `RED and FAVORITE tabs are never filtered by the read participated toggle`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null

            vm.selectTab(FlagTab.Red)
            flags.emit(
                FlagType.RED,
                FlagsResult.Success(
                    listOf(
                        stubFlag(10, FlagType.RED, hasUnread = true),
                        stubFlag(11, FlagType.RED, hasUnread = false),
                    ),
                ),
            )
            val red = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "RED must include both read and unread regardless of the toggle",
                listOf(10, 11),
                flatTopics(red).map { it.topicId },
            )

            vm.selectTab(FlagTab.Favorite)
            flags.emit(
                FlagType.FAVORITE,
                FlagsResult.Success(
                    listOf(
                        stubFlag(20, FlagType.FAVORITE, hasUnread = false),
                        stubFlag(21, FlagType.FAVORITE, hasUnread = true),
                    ),
                ),
            )
            val favorite = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "FAVORITE must include both read and unread regardless of the toggle",
                listOf(20, 21),
                flatTopics(favorite).map { it.topicId },
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sections reflect the canonical order emitted by the forum repository`() = runTest {
        // #179: the grouped sections follow the categories order, not the flags arrival order.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10, 13))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(100, FlagType.CYAN, cat = 13),
                        stubFlag(200, FlagType.CYAN, cat = 1),
                    ),
                ),
            )
            val success = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1, 10, 13), success.sections.map { it.catId })
            assertEquals(listOf(200), success.sections.first { it.catId == 1 }.topics.map { it.topicId })
            assertTrue(success.sections.first { it.catId == 10 }.topics.isEmpty())
            assertEquals(listOf(100), success.sections.first { it.catId == 13 }.topics.map { it.topicId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flags arriving before categories render immediately with the hard-coded fallback order`() = runTest {
        // 10bis: cold start where Success(flags) lands before observeCategories emits — the
        // fallback order must drive the sections so no flag is lost, then the real order applies.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(autoEmit = false) // hold categories back
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            // observeCategories has emitted nothing yet → fallback order is used.
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            val onFallback = awaitItem() as FlagsListUiState.Success
            assertEquals("fallback exposes the 19 hard-coded categories", 19, onFallback.sections.size)
            assertEquals(listOf(1), flatTopics(onFallback).map { it.topicId })

            // Real catalogue arrives with a narrower set → sections re-derive, flag kept.
            forum.emitCategories(ForumResult.Success(categories(listOf(1, 10))))
            val onReal = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1, 10), onReal.sections.map { it.catId })
            assertEquals(listOf(1), flatTopics(onReal).map { it.topicId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categories Loading and Failure fall back to the hard-coded order without losing flags`() = runTest {
        // 11 + 11ter: a Loading/Failure on the categories side must NOT turn a flags Success
        // into a Failure screen — the fallback order is used and the flags still render.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(autoEmit = false)
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            forum.emitCategories(ForumResult.Loading)
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(7, FlagType.CYAN, cat = 1))))
            val onLoading = awaitItem() as FlagsListUiState.Success
            assertEquals(19, onLoading.sections.size)
            assertEquals(listOf(7), flatTopics(onLoading).map { it.topicId })

            // A Failure on the categories side must NOT turn the screen into a Failure. Because
            // both Loading and Failure map to the SAME fallback Success state, stateIn dedupes
            // the identical value — so we change the FLAGS too, proving the new distinct state
            // is still a Success (fallback order, flag kept) and never a Failure.
            forum.emitCategories(ForumResult.Failure(IllegalStateException("categories down")))
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(8, FlagType.CYAN, cat = 1))))
            val onFailure = awaitItem() as FlagsListUiState.Success
            assertEquals(19, onFailure.sections.size)
            assertEquals(listOf(8), flatTopics(onFailure).map { it.topicId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an empty categories Success falls back to the hard-coded order, never a blank body`() = runTest {
        // Guards the double-empty edge: zero flags AND a Success carrying an empty catalogue must
        // NOT collapse to zero sections (a fully blank body). An empty Success is treated as
        // « no catalogue yet » → FALLBACK_CATEGORY_ORDER drives the 19 known sections.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(autoEmit = false)
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            forum.emitCategories(ForumResult.Success(emptyList()))
            // Double-empty: no flags + empty catalogue Success.
            flags.emit(FlagType.CYAN, FlagsResult.Success(emptyList()))
            val onEmptyCatalogue = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "empty Success catalogue must use the 19-category fallback, not zero sections",
                19,
                onEmptyCatalogue.sections.size,
            )
            assertTrue(
                "every fallback section is empty when there are no flags",
                onEmptyCatalogue.sections.all { it.topics.isEmpty() },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `categories are observed once for the authenticated tab and never refreshed`() = runTest {
        // 11bis: exactly one categories subscription for the active authenticated tab, and the
        // flags screen never calls refreshCategories (the read path / 24h cache owns that).
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            awaitItem()
            assertEquals("one categories subscription for the active tab", 1, forum.observeCategoriesSubscriptions)

            vm.refresh()
            assertEquals(0, forum.refreshCategoriesCalls)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `switching authenticated pseudo clears the private flags cache`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN))))
            awaitItem()

            auth.emit(AuthState.Authenticated("other"))

            assertEquals(2, flags.clearSessionCacheCallCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestRemoveFlag moves to Confirming and confirm runs through to Success`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)
        val flag = stubFlag(1, FlagType.CYAN)

        vm.removeFlagState.test {
            assertEquals(RemoveFlagState.Idle, awaitItem())

            vm.requestRemoveFlag(flag)
            assertEquals(RemoveFlagState.Confirming(flag), awaitItem())

            // Gate the repository so the Removing state is observable before it resolves.
            flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred()
            vm.confirmRemoveFlag()
            assertEquals(RemoveFlagState.Removing(flag), awaitItem())

            flags.removeFlagResult.complete(Result.success(Unit))
            assertEquals(RemoveFlagState.Idle, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(flag), flags.removeFlagCalls)
        assertEquals(RemoveFlagEvent.Success(flag.title), vm.removeFlagEvent.value)
    }

    @Test
    fun `cancelRemoveFlag returns to Idle without calling the repository`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)

        vm.requestRemoveFlag(stubFlag(1, FlagType.CYAN))
        vm.cancelRemoveFlag()

        assertEquals(RemoveFlagState.Idle, vm.removeFlagState.value)
        assertTrue("cancel must not call removeFlag", flags.removeFlagCalls.isEmpty())
    }

    @Test
    fun `confirmRemoveFlag failure emits a Failure event`() = runTest {
        val flags = FakeFlagRepository()
        flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred(
            Result.failure(IllegalStateException("delflag refused")),
        )
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)
        val flag = stubFlag(2, FlagType.FAVORITE)

        vm.requestRemoveFlag(flag)
        vm.confirmRemoveFlag()

        assertEquals(RemoveFlagState.Idle, vm.removeFlagState.value)
        assertEquals(RemoveFlagEvent.Failure(flag.title), vm.removeFlagEvent.value)
    }

    @Test
    fun `requestRemoveFlag is ignored while a removal is in flight`() = runTest {
        val flags = FakeFlagRepository()
        flags.removeFlagResult = kotlinx.coroutines.CompletableDeferred()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)
        val firstFlag = stubFlag(1, FlagType.CYAN)

        vm.requestRemoveFlag(firstFlag)
        vm.confirmRemoveFlag() // -> Removing, suspended on the deferred

        // A second request while in flight must be a no-op (anti double-tap).
        vm.requestRemoveFlag(stubFlag(2, FlagType.CYAN))
        assertEquals(RemoveFlagState.Removing(firstFlag), vm.removeFlagState.value)

        flags.removeFlagResult.complete(Result.success(Unit))
    }

    @Test
    fun `consumeRemoveFlagEvent clears the one-shot event`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = FlagsViewModel(auth, flags, forum)
        val flag = stubFlag(3, FlagType.RED)

        vm.requestRemoveFlag(flag)
        vm.confirmRemoveFlag()
        assertEquals(RemoveFlagEvent.Success(flag.title), vm.removeFlagEvent.value)

        vm.consumeRemoveFlagEvent()
        assertNull(vm.removeFlagEvent.value)
    }

    /** Flattens the grouped sections back to the topics order for assertions on flag content. */
    private fun flatTopics(state: FlagsListUiState.Success): List<Flag> =
        state.sections.flatMap { it.topics }

    private fun categories(ids: List<Int>): List<Category> =
        ids.map { Category(id = it, name = "Cat $it", forceSubcat = false, subcategoryCount = 0) }

    private fun stubFlag(
        topicId: Int,
        type: FlagType,
        hasUnread: Boolean = true,
        cat: Int = 1,
    ): Flag = Flag(
        cat = cat,
        subcat = null,
        topicId = topicId,
        title = "Topic $topicId",
        totalPages = 1,
        replyCount = 0,
        type = type,
        hasUnread = hasUnread,
        lastReadPage = 1,
        lastPostReadId = null,
        firstPostAuthor = "",
        lastReplyAuthor = "",
        lastReplyAt = "",
    )

    private class FakeAuthRepository(
        initial: AuthState,
        private val flagRepository: FakeFlagRepository? = null,
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)
        var logoutCalled: Boolean = false
            private set

        /**
         * Snapshot of [FakeFlagRepository.clearSessionCacheCallCount] taken at the moment
         * this fake's [logout] runs. The contract pinned by `logout clears the private
         * flags cache before resetting auth state` is: the ViewModel must have called
         * `clearSessionCache()` *before* delegating to `AuthRepository.logout()`. If the
         * order is ever flipped, this number stays at its pre-logout baseline and the
         * test fails.
         */
        var cacheClearsObservedBeforeLogout: Int = 0
            private set

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()
        override suspend fun login(pseudo: String, password: String) =
            Result.failure<AuthState.Authenticated>(LoginError.Unknown("not used"))

        override suspend fun logout() {
            cacheClearsObservedBeforeLogout = flagRepository?.clearSessionCacheCallCount ?: 0
            logoutCalled = true
            state.value = AuthState.Anonymous
        }

        fun emit(next: AuthState) {
            state.value = next
        }
    }

    private class FakeFlagRepository : FlagRepository {
        private val perType: Map<FlagType, MutableSharedFlow<FlagsResult>> = FlagType.entries
            .associateWith { MutableSharedFlow(replay = 1, extraBufferCapacity = 4) }
        var refreshCalls: List<FlagType> = emptyList()
            private set
        var clearSessionCacheCallCount: Int = 0
            private set
        var removeFlagCalls: List<Flag> = emptyList()
            private set

        /**
         * Result the next [removeFlag] call returns. A [CompletableDeferred] lets a test gate
         * the suspension so it can assert the intermediate [RemoveFlagState.Removing] before the
         * call resolves.
         */
        var removeFlagResult: kotlinx.coroutines.CompletableDeferred<Result<Unit>> =
            kotlinx.coroutines.CompletableDeferred(Result.success(Unit))

        override fun observe(type: FlagType): Flow<FlagsResult> =
            perType.getValue(type).asSharedFlow()

        override suspend fun refresh(type: FlagType) {
            refreshCalls = refreshCalls + type
        }

        override fun clearSessionCache() {
            clearSessionCacheCallCount += 1
        }

        override suspend fun removeFlag(flag: Flag): Result<Unit> {
            removeFlagCalls = removeFlagCalls + flag
            return removeFlagResult.await()
        }

        suspend fun emit(type: FlagType, result: FlagsResult) {
            perType.getValue(type).emit(result)
        }
    }

    /**
     * Fake [ForumRepository] for the grouped-flags tests. Exposes subscription / refresh
     * counters so a test can assert the ViewModel does NOT trigger a spurious public
     * categories fetch (Anonymous / Super) and NEVER calls [refreshCategories] (cf. §5).
     *
     * When [autoEmit] is true the categories flow replays a [ForumResult.Success] built from
     * [catIds] on every subscription (mirrors the warm 24h memory cache). When false, the test
     * drives emissions explicitly via [emitCategories] to exercise the cold-start ordering.
     */
    private class FakeForumRepository(
        private val catIds: List<Int> = emptyList(),
        private val autoEmit: Boolean = true,
    ) : ForumRepository {
        private val categoriesFlow = MutableSharedFlow<ForumResult<List<Category>>>(
            replay = 1,
            extraBufferCapacity = 8,
        )

        var observeCategoriesSubscriptions: Int = 0
            private set
        var refreshCategoriesCalls: Int = 0
            private set

        init {
            if (autoEmit) {
                val cats = catIds.map {
                    Category(id = it, name = "Cat $it", forceSubcat = false, subcategoryCount = 0)
                }
                categoriesFlow.tryEmit(ForumResult.Success(cats))
            }
        }

        suspend fun emitCategories(result: ForumResult<List<Category>>) {
            categoriesFlow.emit(result)
        }

        override fun observeCategories(): Flow<ForumResult<List<Category>>> =
            categoriesFlow.asSharedFlow().onSubscription { observeCategoriesSubscriptions += 1 }

        override suspend fun refreshCategories() {
            refreshCategoriesCalls += 1
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
    }
}
