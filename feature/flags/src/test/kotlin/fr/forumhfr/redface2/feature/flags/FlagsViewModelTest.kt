package fr.forumhfr.redface2.feature.flags

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.error.classifyHfrError
import fr.forumhfr.redface2.core.domain.flags.FlagRepository
import fr.forumhfr.redface2.core.domain.flags.FlagsResult
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
    fun `re-tapping the already selected Cyan tab toggles its unread-only filter`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

        // CYAN defaults to unreadOnly = true (« +lus » off); re-tapping it flips it off, then on.
        assertEquals(true, vm.flagsViewSettings.value.unreadOnly)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(false, vm.flagsViewSettings.value.unreadOnly)
        vm.selectTab(FlagTab.Cyan)
        assertEquals(true, vm.flagsViewSettings.value.unreadOnly)
        // Re-tap must not switch the selected tab or trigger a refetch.
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertTrue("re-tap must not refetch", flags.refreshCalls.isEmpty())
    }

    @Test
    fun `selecting Cyan from another tab does not toggle the filter`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

        vm.selectTab(FlagTab.Red)
        // RED defaults to unreadOnly = false (show all).
        assertEquals(false, vm.flagsViewSettings.value.unreadOnly)

        // First tap on Cyan from RED selects it WITHOUT toggling — CYAN keeps its default (true),
        // it is not flipped off as a re-tap would do.
        vm.selectTab(FlagTab.Cyan)
        assertEquals(FlagTab.Cyan, vm.selectedTab.value)
        assertEquals(true, vm.flagsViewSettings.value.unreadOnly)
    }

    @Test
    fun `rapid double re-tap on Cyan flips twice via the optimistic value`() = runTest {
        // #317 review (cf. #309 shim): a re-tap reads the RESOLVED settings (an async DataStore
        // flow). Without the optimistic [pendingCyanUnreadOnly], a second rapid re-tap before the first
        // write commits would read the SAME lagging value and lose the toggle. Gate the write so
        // both re-taps fire before either persists, then prove the value still ends at its start
        // (true → false → true) — i.e. the second tap flipped from the optimistic `false`.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository() // CYAN default unreadOnly = true
        prefs.blockUnreadOnlySetUntil = kotlinx.coroutines.CompletableDeferred()
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.selectTab(FlagTab.Cyan) // re-tap: true → write(false) gated, pending = false
        vm.selectTab(FlagTab.Cyan) // re-tap: reads optimistic false → write(true) gated, pending = true
        prefs.blockUnreadOnlySetUntil!!.complete(Unit) // release both gated writes (FIFO)

        assertEquals(
            "two rapid re-taps must net back to the start, not lose the second flip",
            true,
            vm.flagsViewSettings.value.unreadOnly,
        )
    }

    @Test
    fun `an in-flight RED write never clobbers the CYAN re-tap shim`() = runTest {
        // #317 Codex review: the optimistic shim is CYAN-scoped, so a concurrent (or late-completing)
        // RED/FAVORITE write must never touch — let alone clear — CYAN's pending flip. Gate ONLY the
        // RED write so it stays in flight while CYAN is re-tapped, then release it and assert CYAN
        // kept its flip.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository() // CYAN default unreadOnly = true
        prefs.blockUnreadOnlySetForType = FlagType.RED
        prefs.blockUnreadOnlySetUntil = kotlinx.coroutines.CompletableDeferred()
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.selectTab(FlagTab.Red)
        vm.setFlagsUnreadOnly(false) // RED write goes in flight (gated), must not touch the CYAN shim

        vm.selectTab(FlagTab.Cyan) // select CYAN (first tap from RED)
        vm.selectTab(FlagTab.Cyan) // re-tap CYAN → flips true → false via its own shim
        assertEquals("CYAN re-tap flips regardless of the in-flight RED write", false, vm.cyanUnreadOnly.value)

        prefs.blockUnreadOnlySetUntil!!.complete(Unit) // late RED completion
        assertEquals("late RED completion must not disturb CYAN's value", false, vm.cyanUnreadOnly.value)
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
        val vm = viewModel(auth, flags, forum)
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Failure(expired))
            val failure = awaitItem() as FlagsListUiState.Failure
            assertTrue(
                "expected SessionExpiredException to traverse the stack — got ${failure.cause::class.simpleName}",
                failure.cause is SessionExpiredException,
            )
            // #324 non-régression CTA : la session expirée doit rester classée Other (jamais
            // Network/ServerDown) pour que la branche session de FlagsRoute garde la priorité.
            assertEquals(HfrErrorKind.Other, classifyHfrError(failure.cause))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flagsState propagates an HfrServerException cause so the route can render the outage`() = runTest {
        // #324 — the drapeaux REST fetch (HfrApiClient) raises HfrServerException on a 5xx.
        // FlagsRoute classifies `Failure.cause` at render time, so the typed exception must
        // traverse repository → ViewModel → exposed state un-wrapped, like SessionExpired.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)
        val outage = HfrServerException(code = 500, url = "https://forum.hardware.fr/webservices/rest_api.php")

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Failure(outage))
            val failure = awaitItem() as FlagsListUiState.Failure
            assertEquals(HfrErrorKind.ServerDown, classifyHfrError(failure.cause))
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
        val vm = viewModel(auth, flags, forum)

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
    fun `setFlagsUnreadOnly false reveals read CYAN topics without refetch`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

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
            // CYAN defaults to unreadOnly = true, so the read topic (2) is filtered out at first.
            assertEquals(listOf(1), flatTopics(awaitItem() as FlagsListUiState.Success).map { it.topicId })

            vm.setFlagsUnreadOnly(false)
            // No new refresh() call — the toggle alone must re-emit the unfiltered list because
            // flagsState combines the source flow with the resolved unreadOnly value.
            val full = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1, 2), flatTopics(full).map { it.topicId })
            assertTrue("toggle must not trigger a network refresh", flags.refreshCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `RED and FAVORITE show every topic by default (unread-only off)`() = runTest {
        // #317: unreadOnly is type-aware — RED and FAVORITE default to false (show all), unlike
        // CYAN. With no toggle set, both list read and unread topics.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

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
                "RED defaults to show all (unreadOnly false)",
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
                "FAVORITE defaults to show all (unreadOnly false)",
                listOf(20, 21),
                flatTopics(favorite).map { it.topicId },
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsUnreadOnly true filters RED to unread topics only`() = runTest {
        // #317: the filter now generalises to every type — toggling unreadOnly on RED keeps only
        // the topics with an unread post, per the selected tab's own value.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

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
            assertEquals(
                "RED shows all before the toggle",
                listOf(10, 11),
                flatTopics(awaitItem() as FlagsListUiState.Success).map { it.topicId },
            )

            vm.setFlagsUnreadOnly(true) // RED is selected → flips RED's per-type value.
            val filtered = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "RED now keeps only the unread topic, without a refetch",
                listOf(10),
                flatTopics(filtered).map { it.topicId },
            )
            assertTrue("toggle must not trigger a network refresh", flags.refreshCalls.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sections reflect the canonical order emitted by the forum repository`() = runTest {
        // #179: the grouped sections follow the categories order, not the flags arrival order.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10, 13))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

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
            assertEquals(listOf(1, 10, 13), sections(success).map { it.catId })
            assertEquals(listOf(200), sections(success).first { it.catId == 1 }.topics.map { it.topicId })
            assertTrue(sections(success).first { it.catId == 10 }.topics.isEmpty())
            assertEquals(listOf(100), sections(success).first { it.catId == 13 }.topics.map { it.topicId })
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
        val vm = viewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            // observeCategories has emitted nothing yet → fallback order is used.
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            val onFallback = awaitItem() as FlagsListUiState.Success
            assertEquals("fallback exposes the 19 hard-coded categories", 19, sections(onFallback).size)
            assertEquals(listOf(1), flatTopics(onFallback).map { it.topicId })

            // Real catalogue arrives with a narrower set → sections re-derive, flag kept.
            forum.emitCategories(ForumResult.Success(categories(listOf(1, 10))))
            val onReal = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1, 10), sections(onReal).map { it.catId })
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
        val vm = viewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            forum.emitCategories(ForumResult.Loading)
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(7, FlagType.CYAN, cat = 1))))
            val onLoading = awaitItem() as FlagsListUiState.Success
            assertEquals(19, sections(onLoading).size)
            assertEquals(listOf(7), flatTopics(onLoading).map { it.topicId })

            // A Failure on the categories side must NOT turn the screen into a Failure. Because
            // both Loading and Failure map to the SAME fallback Success state, stateIn dedupes
            // the identical value — so we change the FLAGS too, proving the new distinct state
            // is still a Success (fallback order, flag kept) and never a Failure.
            forum.emitCategories(ForumResult.Failure(IllegalStateException("categories down")))
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(8, FlagType.CYAN, cat = 1))))
            val onFailure = awaitItem() as FlagsListUiState.Success
            assertEquals(19, sections(onFailure).size)
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
        val vm = viewModel(auth, flags, forum)

        vm.flagsState.test {
            awaitItem() // initial null
            forum.emitCategories(ForumResult.Success(emptyList()))
            // Double-empty: no flags + empty catalogue Success.
            flags.emit(FlagType.CYAN, FlagsResult.Success(emptyList()))
            val onEmptyCatalogue = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "empty Success catalogue must use the 19-category fallback, not zero sections",
                19,
                sections(onEmptyCatalogue).size,
            )
            assertTrue(
                "every fallback section is empty when there are no flags",
                sections(onEmptyCatalogue).all { it.topics.isEmpty() },
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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)
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
        val vm = viewModel(auth, flags, forum)

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
        val vm = viewModel(auth, flags, forum)
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
        val vm = viewModel(auth, flags, forum)
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
        val vm = viewModel(auth, flags, forum)
        val flag = stubFlag(3, FlagType.RED)

        vm.requestRemoveFlag(flag)
        vm.confirmRemoveFlag()
        assertEquals(RemoveFlagEvent.Success(flag.title), vm.removeFlagEvent.value)

        vm.consumeRemoveFlagEvent()
        assertNull(vm.removeFlagEvent.value)
    }

    @Test
    fun `flat view preference yields a flat content preserving repository order`() = runTest {
        // #179 follow-up: the legacy flat view must keep the repository order (last reply desc),
        // NOT the category-grouped order — proven here with flags arriving cat 13 then cat 1.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 13))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = false)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

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
            val flat = (awaitItem() as FlagsListUiState.Success).content as FlagsContent.Flat
            assertEquals(listOf(100, 200), flat.flags.map { it.topicId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggling group-by-category pref switches content shape without a refetch`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            assertTrue((awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped)

            prefs.setGroupBy(false)
            assertTrue((awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat)
            assertTrue("a view-mode toggle must never trigger a network refresh", flags.refreshCalls.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hide-read pref drops categories without an unread flag on RED`() = runTest {
        // RED is not read-filtered, so both read and unread reach the grouping: hide-read must
        // drop the all-read category (10) and the empty ones, keeping only the one with an unread.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(hideReadCategories = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            vm.selectTab(FlagTab.Red)
            flags.emit(
                FlagType.RED,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.RED, hasUnread = true, cat = 1),
                        stubFlag(2, FlagType.RED, hasUnread = false, cat = 10),
                    ),
                ),
            )
            val success = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1), sections(success).map { it.catId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cyan +lus override keeps a fully-read category visible under hide-read`() = runTest {
        // The tension the user flagged: « +lus » (unreadOnly off → show read participated topics)
        // must win over « masquer les catégories sans non-lu » so the read cyans stay reachable.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(hideReadCategories = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            vm.setFlagsUnreadOnly(false)
            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true, cat = 1),
                        stubFlag(2, FlagType.CYAN, hasUnread = false, cat = 10),
                    ),
                ),
            )
            val success = awaitItem() as FlagsListUiState.Success
            assertEquals(
                "with +lus on, a category holding only a read cyan must survive hide-read",
                listOf(1, 10),
                sections(success).map { it.catId },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cyan without +lus under hide-read shows only categories with an unread cyan`() = runTest {
        // Without « +lus », the #154 filter removes the read cyan first, so its category becomes
        // empty and hide-read drops it — leaving only the category with an actionable unread.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(hideReadCategories = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(
                FlagType.CYAN,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.CYAN, hasUnread = true, cat = 1),
                        stubFlag(2, FlagType.CYAN, hasUnread = false, cat = 10),
                    ),
                ),
            )
            val success = awaitItem() as FlagsListUiState.Success
            assertEquals(listOf(1), sections(success).map { it.catId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hide-read with no unread flag collapses the grouped sections to empty`() = runTest {
        // Codex review: when hide-read is on and NO category has an unread flag (all read, or CYAN
        // all-read with +lus off), the grouped content must be Grouped(emptyList()). The screen
        // renders a placeholder for this state so the body never blanks (anti #229 regression);
        // this test pins the state contract the screen relies on.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1, 10))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(hideReadCategories = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            vm.selectTab(FlagTab.Red) // RED isn't read-filtered: the all-read flags reach grouping.
            flags.emit(
                FlagType.RED,
                FlagsResult.Success(
                    listOf(
                        stubFlag(1, FlagType.RED, hasUnread = false, cat = 1),
                        stubFlag(2, FlagType.RED, hasUnread = false, cat = 10),
                    ),
                ),
            )
            val grouped = (awaitItem() as FlagsListUiState.Success).content as FlagsContent.Grouped
            assertTrue(
                "every category is fully read → hide-read collapses to zero sections",
                grouped.sections.isEmpty(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `per-tab override resolves a per-type flat view while another tab stays grouped`() = runTest {
        // #309: with the override on, each tab reads its own settings. Here CYAN is customised flat
        // while RED keeps the global grouped default — the resolution must be tab-scoped.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true, perTabOverride = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)
        prefs.setFlagsGroupByCategoryForType(FlagType.CYAN, false)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            assertTrue(
                "CYAN's per-type override is flat",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat,
            )

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED, cat = 1))))
            assertTrue(
                "RED has no override → falls back to the global grouped default",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsGroupByCategory writes the global scope when the override is off`() = runTest {
        // Override off: the bottom-sheet write must hit the GLOBAL key, so every tab flips, not
        // just the one currently selected.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            assertTrue((awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped)

            vm.setFlagsGroupByCategory(false)
            assertTrue(
                "CYAN flips to flat after the global write",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat,
            )

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED, cat = 1))))
            assertTrue(
                "RED is flat too → the write landed on the global key, not a per-type one",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsGroupByCategory writes the per-type scope when the override is on`() = runTest {
        // Override on: the write must hit only the SELECTED tab's per-type key, leaving the others
        // (and the global default) untouched.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true, perTabOverride = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            assertTrue((awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped)

            vm.setFlagsGroupByCategory(false) // CYAN selected → CYAN per-type only.
            assertTrue(
                "CYAN flips to flat via its per-type key",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat,
            )

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED, cat = 1))))
            assertTrue(
                "RED stays grouped → the write did NOT touch the global default",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flagsViewSettings tracks the resolved settings of the selected tab`() = runTest {
        // The bottom sheet reads flagsViewSettings to render its switches; under the override it
        // must follow the selected tab's resolution.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true, perTabOverride = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)
        prefs.setFlagsGroupByCategoryForType(FlagType.RED, false)

        vm.flagsViewSettings.test {
            assertTrue("CYAN resolves to the global grouped default", awaitItem().groupByCategory)
            vm.selectTab(FlagTab.Red)
            assertFalse("RED resolves to its per-type flat override", awaitItem().groupByCategory)
            vm.selectTab(FlagTab.Cyan)
            assertTrue("back on CYAN, grouped again", awaitItem().groupByCategory)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagsHideReadCategories writes the global scope when the override is off`() = runTest {
        // hide-read routing mirror of the group-by tests; asserted via flagsViewSettings.value since
        // the cross-tab value is identical (global), which a StateFlow would dedup out of a turbine.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository()
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        assertFalse(vm.flagsViewSettings.value.hideReadCategories)
        vm.setFlagsHideReadCategories(true)
        assertTrue("CYAN reflects the write", vm.flagsViewSettings.value.hideReadCategories)
        vm.selectTab(FlagTab.Red)
        assertTrue(
            "RED is on too → the write landed on the global key",
            vm.flagsViewSettings.value.hideReadCategories,
        )
    }

    @Test
    fun `setFlagsHideReadCategories writes the per-type scope when the override is on`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(perTabOverride = true)
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.setFlagsHideReadCategories(true) // CYAN selected
        assertTrue("CYAN per-type hide-read on", vm.flagsViewSettings.value.hideReadCategories)
        vm.selectTab(FlagTab.Red)
        assertFalse(
            "RED untouched → global false; the write did not leak across tabs",
            vm.flagsViewSettings.value.hideReadCategories,
        )
    }

    @Test
    fun `toggle routing uses the optimistic master value before the override write persists`() = runTest {
        // #309 Codex review: routing must honour the just-flipped master even while its DataStore
        // write is still in flight. Gate the persisted master write so `perTab` never updates, then
        // assert the group write went to the PER-TYPE key (driven by the optimistic value), not the
        // global one. (Resolution/display catches up once the master persists; routing must not.)
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository() // persisted override OFF
        prefs.blockPerTabOverrideSetUntil = kotlinx.coroutines.CompletableDeferred()
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.setFlagsPerTabOverride(true) // optimistic ON; persisted write GATED (never commits here)
        vm.setFlagsGroupByCategory(false)

        assertTrue(
            "the group write must hit the per-type key via the optimistic master",
            prefs.lastGroupByWriteWasPerType == true,
        )
    }

    @Test
    fun `flipping the override then a toggle in sequence routes per-type`() = runTest {
        // Regression guard for the write-routing fix: the toggle write must honour the master value
        // the user just flipped (read from flagsPerTabOverride.value, consistent with the rendered
        // switch), so it routes per-type and leaves the global default (other tabs) untouched.
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository(groupByCategory = true) // override OFF initially
        val vm = FlagsViewModel(auth, flags, forum, prefs)

        vm.flagsState.test {
            awaitItem() // initial null
            flags.emit(FlagType.CYAN, FlagsResult.Success(listOf(stubFlag(1, FlagType.CYAN, cat = 1))))
            assertTrue((awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped)

            vm.setFlagsPerTabOverride(true) // flip master…
            vm.setFlagsGroupByCategory(false) // …then immediately flip group on CYAN
            assertTrue(
                "CYAN flips to flat via its per-type key",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Flat,
            )

            vm.selectTab(FlagTab.Red)
            flags.emit(FlagType.RED, FlagsResult.Success(listOf(stubFlag(2, FlagType.RED, cat = 1))))
            assertTrue(
                "RED stays grouped → the toggle honoured the just-flipped master and wrote per-type",
                (awaitItem() as FlagsListUiState.Success).content is FlagsContent.Grouped,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Builds the ViewModel with a default (grouped-on, hide-read-off) [FakeUserPreferencesRepository]
     * so the existing tests keep asserting on the grouped sections. Tests that exercise the flat
     * view or the hide-read filter pass an explicit [prefs].
     */
    private fun viewModel(
        auth: FakeAuthRepository,
        flags: FakeFlagRepository,
        forum: FakeForumRepository,
        prefs: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
    ): FlagsViewModel = FlagsViewModel(auth, flags, forum, prefs)

    /** Flattens whatever content shape into the topics order for assertions on flag content. */
    private fun flatTopics(state: FlagsListUiState.Success): List<Flag> =
        when (val content = state.content) {
            is FlagsContent.Grouped -> content.sections.flatMap { it.topics }
            is FlagsContent.Flat -> content.flags
        }

    /** Extracts the grouped sections, failing the cast if the content was flat (test misuse). */
    private fun sections(state: FlagsListUiState.Success): List<FlagCategorySection> =
        (state.content as FlagsContent.Grouped).sections

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

    /**
     * Fake [UserPreferencesRepository] modelling the Drapeaux view preferences the ViewModel reads:
     * the global group-by-category / hide-read pair, the #309 per-tab override master switch, the
     * per-type layout overrides, and the #317 per-type « non-lus uniquement » value — as writable
     * hot flows. [observeFlagsViewSettings] resolves them the same way the real DataStore impl does
     * (layout: override off → global; on → per-type value, else global fallback; unreadOnly: ALWAYS
     * per-type with a type-aware default — CYAN true, RED/FAVORITE false). The proxy and topic-cache
     * members are stubbed at their defaults (untouched here).
     */
    private class FakeUserPreferencesRepository(
        groupByCategory: Boolean = true,
        hideReadCategories: Boolean = false,
        perTabOverride: Boolean = false,
    ) : UserPreferencesRepository {
        private val groupBy = MutableStateFlow(groupByCategory)
        private val hideRead = MutableStateFlow(hideReadCategories)
        private val perTab = MutableStateFlow(perTabOverride)
        private val perTypeGroup: Map<FlagType, MutableStateFlow<Boolean?>> =
            FlagType.entries.associateWith { MutableStateFlow<Boolean?>(null) }
        private val perTypeHide: Map<FlagType, MutableStateFlow<Boolean?>> =
            FlagType.entries.associateWith { MutableStateFlow<Boolean?>(null) }
        // #317 — per-type « non-lus uniquement », null = unset → type-aware default applied at read.
        private val perTypeUnread: Map<FlagType, MutableStateFlow<Boolean?>> =
            FlagType.entries.associateWith { MutableStateFlow<Boolean?>(null) }

        /** When set, holds the persisted master write so a test can prove routing uses the
         * OPTIMISTIC value (the persisted `perTab` never updates while gated). */
        var blockPerTabOverrideSetUntil: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        /** Records whether the most recent group-by write hit the per-type key (`true`) or the
         * global key (`false`) — lets a test assert the routing decision directly. */
        var lastGroupByWriteWasPerType: Boolean? = null
            private set

        override fun observeProxyConfig(): Flow<ProxyConfig> = MutableStateFlow(ProxyConfig())
        override suspend fun saveProxyConfig(config: ProxyConfig) = Unit
        override fun readProxyConfigForNetworkBootstrap(): ProxyConfig = ProxyConfig()
        override fun observeIgnoreTopicCache(): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun setIgnoreTopicCache(enabled: Boolean) = Unit

        override fun observeFlagsGroupByCategory(): Flow<Boolean> = groupBy
        override suspend fun setFlagsGroupByCategory(enabled: Boolean) {
            groupBy.value = enabled
            lastGroupByWriteWasPerType = false
        }

        override fun observeFlagsHideReadCategories(): Flow<Boolean> = hideRead
        override suspend fun setFlagsHideReadCategories(enabled: Boolean) {
            hideRead.value = enabled
        }

        override fun observeFlagsPerTabOverride(): Flow<Boolean> = perTab
        override suspend fun setFlagsPerTabOverride(enabled: Boolean) {
            blockPerTabOverrideSetUntil?.await()
            perTab.value = enabled
        }

        override fun observeFlagsViewSettings(type: FlagType): Flow<FlagsViewSettings> {
            // Layout resolution (#309), then fold in the always-per-type unreadOnly (#317). Nested
            // combine because the typed `combine` overload tops out at 5 sources.
            val layout = combine(
                groupBy,
                hideRead,
                perTab,
                perTypeGroup.getValue(type),
                perTypeHide.getValue(type),
            ) { global, globalHide, override, typeGroup, typeHide ->
                if (override) {
                    (typeGroup ?: global) to (typeHide ?: globalHide)
                } else {
                    global to globalHide
                }
            }
            return combine(layout, perTypeUnread.getValue(type)) { (group, hide), unread ->
                FlagsViewSettings(group, hide, unread ?: defaultUnreadOnly(type))
            }
        }

        // Mirrors DataStoreUserPreferencesRepository.defaultUnreadOnly (CYAN actionable by default).
        private fun defaultUnreadOnly(type: FlagType): Boolean = type == FlagType.CYAN

        override suspend fun setFlagsGroupByCategoryForType(type: FlagType, enabled: Boolean) {
            perTypeGroup.getValue(type).value = enabled
            lastGroupByWriteWasPerType = true
        }

        override suspend fun setFlagsHideReadCategoriesForType(type: FlagType, enabled: Boolean) {
            perTypeHide.getValue(type).value = enabled
        }

        /** When set, gates the unreadOnly write so a test can prove the re-tap uses the OPTIMISTIC
         * value while the DataStore round-trip is still in flight. [blockUnreadOnlySetForType] scopes
         * the gate to one type (null = gate every type). */
        var blockUnreadOnlySetUntil: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        var blockUnreadOnlySetForType: FlagType? = null

        override suspend fun setFlagsUnreadOnlyForType(type: FlagType, enabled: Boolean) {
            if (blockUnreadOnlySetForType == null || blockUnreadOnlySetForType == type) {
                blockUnreadOnlySetUntil?.await()
            }
            perTypeUnread.getValue(type).value = enabled
        }

        // #286 — theme prefs are irrelevant to FlagsViewModel; stubbed at their defaults.
        override fun observeThemeMode(): Flow<ThemeMode> = MutableStateFlow(ThemeMode.SYSTEM)

        override suspend fun setThemeMode(mode: ThemeMode) = Unit

        override fun observeAmoledEnabled(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setAmoledEnabled(enabled: Boolean) = Unit

        override fun observeTopicTopBarAutoHide(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicTopBarAutoHide(enabled: Boolean) = Unit

        // #312 — confirm-before-posting is irrelevant to FlagsViewModel; stubbed at its default.
        override fun observeConfirmBeforePosting(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setConfirmBeforePosting(enabled: Boolean) = Unit

        override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setShowDtSection(enabled: Boolean) = Unit

        fun setGroupBy(value: Boolean) {
            groupBy.value = value
        }

        fun setHideRead(value: Boolean) {
            hideRead.value = value
        }

        fun setPerTabOverride(value: Boolean) {
            perTab.value = value
        }
    }
}
