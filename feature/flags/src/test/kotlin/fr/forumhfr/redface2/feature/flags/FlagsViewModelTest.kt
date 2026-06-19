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
import fr.forumhfr.redface2.core.domain.forum.FlagFilterBucket
import fr.forumhfr.redface2.core.domain.forum.ForumRepository
import fr.forumhfr.redface2.core.domain.forum.ForumResult
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.preferences.DisplayDensity
import fr.forumhfr.redface2.core.domain.preferences.FlagsViewSettings
import fr.forumhfr.redface2.core.domain.preferences.FontScalePreference
import fr.forumhfr.redface2.core.domain.preferences.AccentColor
import fr.forumhfr.redface2.core.domain.preferences.ImmersiveNavBarReveal
import fr.forumhfr.redface2.core.domain.preferences.ProxyConfig
import fr.forumhfr.redface2.core.domain.preferences.StartScreenPreference
import fr.forumhfr.redface2.core.domain.preferences.ThemeMode
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.model.editor.EditorImageInsert
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Category
import fr.forumhfr.redface2.core.model.Flag
import fr.forumhfr.redface2.core.model.FlagType
import fr.forumhfr.redface2.core.model.SubCategory
import fr.forumhfr.redface2.core.model.TopicListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
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

    /** Frozen clock for every test that does not exercise the #378 throttle window. */
    private val fixedClock: Clock =
        Clock.fixed(Instant.parse("2026-06-11T12:00:00Z"), ZoneOffset.UTC)

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
    fun `selectTab to a different tab recalls the list to the top (#106)`() = runTest {
        val flags = FakeFlagRepository()
        val forum = FakeForumRepository(catIds = listOf(1))
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, forum)

        assertFalse("no recall before any tab change", vm.recallListToTop.value)

        vm.selectTab(FlagTab.Red) // real transition (tap or swipe commit both route here)
        assertTrue("a real tab switch recalls the list to the top", vm.recallListToTop.value)

        vm.consumeRecallListToTop()
        assertFalse(vm.recallListToTop.value)

        vm.selectTab(FlagTab.Red) // re-tap the same tab: no-op, no recall (keeps scroll position)
        assertFalse("re-tapping the already-selected tab must not recall", vm.recallListToTop.value)
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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)
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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)
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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
        val vm = viewModel(auth, flags, forum, prefs)

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
    // #378 — auto-refresh on landing: pref gate, auth gate, throttle window.

    @Test
    fun `maybeAutoRefresh refreshes the current tab when authenticated`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.maybeAutoRefresh()

        assertEquals(listOf(FlagType.CYAN), flags.refreshCalls)
    }

    @Test
    fun `maybeAutoRefresh recalls the list to the top on a landing refresh (#546)`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.maybeAutoRefresh()
        assertTrue(vm.recallListToTop.value)

        // One-shot: consuming it disarms the signal so a recomposition / rotation cannot replay the
        // scroll with no fresh refresh behind it (Codex review #546).
        vm.consumeRecallListToTop()
        assertFalse(vm.recallListToTop.value)
    }

    @Test
    fun `a return-from-topic auto-refresh does not recall the list to the top (#546)`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)

        vm.maybeAutoRefresh() // landing refresh → raises the signal
        vm.consumeRecallListToTop() // screen scrolled and consumed it
        vm.onFlagOpened() // user opens a topic…
        vm.maybeAutoRefresh() // …and returns (throttle bypassed): refreshes but must NOT re-raise

        assertEquals(2, flags.refreshCalls.size)
        assertFalse(vm.recallListToTop.value)
    }

    @Test
    fun `maybeAutoRefresh honours the Settings opt-out`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val prefs = FakeUserPreferencesRepository()
        prefs.flagsAutoRefresh.value = false
        val vm = viewModel(auth, flags, FakeForumRepository(), prefs)

        vm.maybeAutoRefresh()

        assertEquals(emptyList<FlagType>(), flags.refreshCalls)
    }

    @Test
    fun `maybeAutoRefresh is a no-op while anonymous`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Anonymous, flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.maybeAutoRefresh()

        assertEquals(emptyList<FlagType>(), flags.refreshCalls)
    }

    @Test
    fun `maybeAutoRefresh throttles rapid landings then allows after the window`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)

        vm.maybeAutoRefresh()
        vm.maybeAutoRefresh() // immediate re-landing (back-and-forth) — throttled
        assertEquals(1, flags.refreshCalls.size)

        clock.now = clock.now.plusSeconds(16) // past the 15 s window
        vm.maybeAutoRefresh()
        assertEquals(2, flags.refreshCalls.size)
    }

    @Test
    fun `tabUnreadFilter pairs each filter value with the tab that produced it`() = runTest {
        // #385/#421 — a tab switch must never be observable as « new tab + previous tab's
        // filter »: each emission carries the tab its unreadOnly value was resolved FOR.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.tabUnreadFilter.test {
            // Initial: Cyan with its type-aware default (unread-only ON).
            assertEquals(FlagTab.Cyan to true, awaitItem())
            vm.selectTab(FlagTab.Red)
            // The RED emission carries RED's own resolved default (false) — atomically.
            assertEquals(FlagTab.Red to false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `maybeAutoRefresh bypasses the throttle when a topic was opened since the last refresh`() = runTest {
        // #378 follow-up (retours dev v118, Dintr-un lemn + bitubo) — coming back from a
        // just-read topic must refresh even inside the 15 s window: the read changed the state.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)

        vm.maybeAutoRefresh() // landing refresh, arms the throttle
        vm.onFlagOpened() // user opens a topic from the list…
        vm.maybeAutoRefresh() // …and comes right back (< 15 s)

        assertEquals(2, flags.refreshCalls.size)
    }

    @Test
    fun `the topic-opened bypass is consumed by the refresh it triggers`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)

        vm.maybeAutoRefresh()
        vm.onFlagOpened()
        vm.maybeAutoRefresh() // bypass consumed here
        vm.maybeAutoRefresh() // plain re-landing without a new read — throttled again

        assertEquals(2, flags.refreshCalls.size)
    }

    @Test
    fun `a read armed while the landing refresh is suspended stays pending for the return`() = runTest {
        // Codex review — maybeAutoRefresh snapshots the opened-generation at CALL time: a topic
        // opened while the landing refresh is still queued/suspended on its pref/auth gates must
        // NOT be consumed by that refresh (it cannot have captured the reading yet), so the
        // actual return from the topic still bypasses the throttle.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)
        advanceUntilIdle() // settle the init collectors

        vm.maybeAutoRefresh() // landing — generation snapshot taken now, body not yet run
        vm.onFlagOpened() // user opens a topic before the landing refresh resumed
        advanceUntilIdle() // landing refresh runs: throttle armed, the read is NOT consumed
        assertEquals(1, flags.refreshCalls.size)

        vm.maybeAutoRefresh() // back from the topic, inside the 15 s window — still bypasses
        advanceUntilIdle()
        assertEquals(2, flags.refreshCalls.size)
    }

    @Test
    fun `a manual refresh consumes the pending topic-opened bypass`() = runTest {
        // The pull captured the post-reading state already; the next landing inside the window
        // must not duplicate the REST fan-out.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val clock = SteppingClock(Instant.parse("2026-06-11T12:00:00Z"))
        val vm = viewModelWithClock(auth, flags, clock)

        vm.maybeAutoRefresh() // arms the throttle
        vm.onFlagOpened()
        vm.refresh() // manual pull right after coming back
        vm.maybeAutoRefresh() // landing inside the window, no new read since the pull

        assertEquals(2, flags.refreshCalls.size)
    }

    @Test
    fun `manual refresh is never throttled by a preceding auto-refresh`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("XaT"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.maybeAutoRefresh()
        vm.refresh() // user pull-to-refresh right after the auto pass

        assertEquals(2, flags.refreshCalls.size)
    }

    // #6 — DT tab: MultiMP list + best-effort MPStorage enrichment.

    @Test
    fun `onDtTabOpened unions inbox MultiMP rows with orphan MPStorage entries`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(
                    stubSummary(threadId = 10, isMultiRecipient = true, lastPage = 3),
                    stubSummary(threadId = 20, isMultiRecipient = false), // 1:1 MP → filtered out
                    stubSummary(threadId = 30, isMultiRecipient = true),
                ),
            ),
        )
        val mpStorage = FakeMpStorageRepository(
            result = MpStorageResult.Found(
                mpDoc(
                    MpStorageFlagEntry(threadId = 10, page = 7, numreponse = null, uri = null),
                    // Orphan: a known DT conversation absent from inbox page 1 → StorageOnly row.
                    MpStorageFlagEntry(threadId = 99, page = 2, numreponse = 555, uri = null),
                ),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            // Inbox MultiMP rows first (1:1 MP 20 dropped), then orphan MPStorage entries.
            assertEquals(listOf(10, 30, 99), content.items.map { it.threadId })
            // Thread 10 has an mpFlags entry → its resume page joins; 30 has none → null badge.
            assertEquals(7, content.items.first { it.threadId == 10 }.resumePage)
            assertNull(content.items.first { it.threadId == 30 }.resumePage)
            // The two inbox rows are InboxBacked, the orphan is StorageOnly carrying its resume page.
            assertTrue(content.items.first { it.threadId == 10 } is DtListItem.InboxBacked)
            val orphan = content.items.first { it.threadId == 99 } as DtListItem.StorageOnly
            assertEquals(2, orphan.resumePage)
            assertEquals(555, orphan.numreponse)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened renders the list without badges when MPStorage is NotFound`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        val mpStorage = FakeMpStorageRepository(result = MpStorageResult.NotFound)
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            assertEquals(listOf(10), content.items.map { it.threadId })
            assertNull("NotFound storage → no resume badge", content.items.single().resumePage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened keeps the list when MPStorage read throws (best-effort)`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        // The MPStorage scan FAILS — it must never fail the conversation list.
        val mpStorage = FakeMpStorageRepository(thrown = IllegalStateException("storage scan down"))
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            assertEquals(listOf(10), content.items.map { it.threadId })
            assertNull("a failed storage read degrades to no badge", content.items.single().resumePage)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened surfaces Empty only when inbox has no MultiMP AND MPStorage has no entry`() = runTest {
        // True-empty: no inbox MultiMP and the default fake returns NotFound (no orphan) → Empty.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(stubSummary(threadId = 20, isMultiRecipient = false)),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            assertEquals(DtListUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened is Content (not Empty) when inbox has no MultiMP but MPStorage has an entry`() = runTest {
        // Regression on the removed `conversations.isEmpty()` early-return: a page-1 box without any
        // MultiMP but with a known DT entry must still render that orphan (#6), never Empty.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(stubSummary(threadId = 20, isMultiRecipient = false)), // 1:1 only
            ),
        )
        val mpStorage = FakeMpStorageRepository(
            result = MpStorageResult.Found(
                mpDoc(MpStorageFlagEntry(threadId = 99, page = 2, numreponse = null, uri = null)),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            assertEquals(listOf(99), content.items.map { it.threadId })
            assertTrue(content.items.single() is DtListItem.StorageOnly)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened dedups a threadId present in both inbox and MPStorage to a single InboxBacked row`() =
        runTest {
            // A threadId in BOTH sources appears once, as InboxBacked (never doubled as StorageOnly),
            // carrying the joined resume badge — and the LazyColumn key (threadId) stays unique.
            val flags = FakeFlagRepository()
            val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
            val messages = FakeMessagesRepository(
                inboxResult = Result.success(
                    inboxPage(stubSummary(threadId = 10, isMultiRecipient = true)),
                ),
            )
            val mpStorage = FakeMpStorageRepository(
                result = MpStorageResult.Found(
                    mpDoc(MpStorageFlagEntry(threadId = 10, page = 4, numreponse = null, uri = null)),
                ),
            )
            val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

            vm.dtListState.test {
                assertEquals(DtListUiState.Loading, awaitItem())
                vm.onDtTabOpened()
                val content = awaitItem() as DtListUiState.Content
                assertEquals(listOf(10), content.items.map { it.threadId })
                val row = content.items.single()
                assertTrue(row is DtListItem.InboxBacked)
                assertEquals(4, row.resumePage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `onDtTabOpened orders inbox rows first then orphans in mpFlags list order`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(
                    stubSummary(threadId = 10, isMultiRecipient = true),
                    stubSummary(threadId = 30, isMultiRecipient = true),
                ),
            ),
        )
        // mpFlags carries one inbox-backed (10) and two orphans (88, 99) in this list order.
        val mpStorage = FakeMpStorageRepository(
            result = MpStorageResult.Found(
                mpDoc(
                    MpStorageFlagEntry(threadId = 10, page = 1, numreponse = null, uri = null),
                    MpStorageFlagEntry(threadId = 88, page = 5, numreponse = null, uri = null),
                    MpStorageFlagEntry(threadId = 99, page = 2, numreponse = null, uri = null),
                ),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            // Inbox order (10, 30) first, then orphans in mpFlags.list order (88, 99).
            assertEquals(listOf(10, 30, 88, 99), content.items.map { it.threadId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened surfaces Error when the inbox load fails`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val expired = SessionExpiredException("https://forum.hardware.fr/login.php")
        val messages = FakeMessagesRepository(inboxResult = Result.failure(expired))
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val error = awaitItem() as DtListUiState.Error
            assertTrue(error.cause is SessionExpiredException)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onDtTabOpened fetches once per session then reuses the loaded list`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        val mpStorage = FakeMpStorageRepository(result = MpStorageResult.NotFound)
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.onDtTabOpened()
        vm.onDtTabOpened() // re-selecting DT must NOT re-scan (the MPStorage scan is expensive)

        assertEquals("only one inbox scan per session", 1, messages.getListCalls)
        assertEquals("only one MPStorage scan per session", 1, mpStorage.fetchCalls)
    }

    @Test
    fun `refreshDt re-runs the scan even after a successful load`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.onDtTabOpened()
        vm.refreshDt()

        assertEquals("explicit refresh bypasses the once-per-session guard", 2, messages.getListCalls)
    }

    @Test
    fun `a failed inbox load can be retried via onDtTabOpened`() = runTest {
        // The inbox is the list's only hard source: an Error resets the guard so a retry re-runs.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(inboxResult = Result.failure(IllegalStateException("net")))
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.onDtTabOpened()
        advanceUntilIdle()
        assertTrue(vm.dtListState.value is DtListUiState.Error)

        messages.inboxResult =
            Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true)))
        vm.onDtTabOpened() // guard was reset by the failure → retry re-runs
        advanceUntilIdle()
        val content = vm.dtListState.value as DtListUiState.Content
        assertEquals(listOf(10), content.items.map { it.threadId })
        assertEquals(2, messages.getListCalls)
    }

    @Test
    fun `switching authenticated pseudo resets the DT list back to Loading`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        // flagsState is an Eagerly stateIn, so clearFlagsCacheIfSessionChanged already observes the
        // auth emissions without an explicit collector here.
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            assertTrue(awaitItem() is DtListUiState.Content)

            auth.emit(AuthState.Authenticated("other")) // account switch must drop the list
            assertEquals(DtListUiState.Loading, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a DT fetch in flight when the account switches never publishes the previous account's list`() = runTest {
        // #6 Codex review (BLOCKING — stale write): a loadDt mid-flight when resetDtState fires (logout
        // / account switch) must NOT republish the previous account's MultiMP into the new session.
        // resetDtState cancels the fetch and bumps the generation, so the in-flight load is dropped and
        // the state stays at the post-switch Loading.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
        )
        messages.blockInboxUntil = kotlinx.coroutines.CompletableDeferred()
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened() // fetch starts but suspends on the gated inbox load (in flight)

            auth.emit(AuthState.Authenticated("other")) // account switch → resetDtState cancels it
            // resetDtState republishes Loading; the value is identical to the seed so the StateFlow
            // dedupes it — no new emission, the state is still Loading.

            messages.blockInboxUntil!!.complete(Unit) // release the now-cancelled previous-account load
            advanceUntilIdle()

            // The stale (xaat) list must never surface: the only state remains Loading.
            assertEquals(DtListUiState.Loading, vm.dtListState.value)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `two rapid refreshDt are latest-wins so the stale earlier result never overwrites the recent one`() =
        runTest {
            // #6 Codex review (concurrent refreshDt): a second refreshDt cancels the first's job and
            // out-generations it, so an earlier in-flight scan can never overwrite the newer result.
            val flags = FakeFlagRepository()
            val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
            val messages = FakeMessagesRepository(
                inboxResult = Result.success(inboxPage(stubSummary(threadId = 10, isMultiRecipient = true))),
            )
            messages.blockInboxUntil = kotlinx.coroutines.CompletableDeferred()
            val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

            vm.refreshDt() // first scan, suspended on the gated inbox load (would yield thread 10)
            messages.inboxResult =
                Result.success(inboxPage(stubSummary(threadId = 99, isMultiRecipient = true)))
            vm.refreshDt() // second scan cancels the first and out-generations it

            messages.blockInboxUntil!!.complete(Unit) // release both; the cancelled first must not win
            advanceUntilIdle()

            val content = vm.dtListState.value as DtListUiState.Content
            assertEquals(
                "the latest refreshDt wins; the cancelled earlier scan's result is dropped",
                listOf(99),
                content.items.map { it.threadId },
            )
        }

    @Test
    fun `the DT list only scans inbox page 1 even when the inbox spans multiple pages`() = runTest {
        // #6 MVP scope: a multi-page sweep is deliberately DEFERRED (it would multiply the cost of the
        // expensive MPStorage scan). Even with totalPages > 1, only page 1 (the most recent
        // conversations) is read, and the list reflects only that page — documented behaviour, the
        // empty-state copy assumes « recent page » semantics.
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                PrivateMessageListPage(
                    page = 1,
                    totalPages = 4, // the inbox spans several pages…
                    items = listOf(stubSummary(threadId = 10, isMultiRecipient = true)),
                ),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtListState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            val content = awaitItem() as DtListUiState.Content
            // …yet only page 1 is reflected; no further page is scanned (multi-page deferred).
            assertEquals(listOf(10), content.items.map { it.threadId })
            assertEquals("only inbox page 1 is ever requested", listOf(1), messages.requestedPages)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // #546 directive XaTriX — DT « non-lus par défaut » + re-tap toggle (« +lus ») + pull-to-refresh.

    @Test
    fun `dtUnreadOnly defaults to true`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        assertTrue("DT opens on the unread subset by default", vm.dtUnreadOnly.value)
    }

    @Test
    fun `re-tapping the already selected DT tab toggles its unread-only filter`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.selectTab(FlagTab.Dt) // first tap from Cyan: just select, no toggle
        assertEquals(FlagTab.Dt, vm.selectedTab.value)
        assertTrue("a plain selection must not toggle the filter", vm.dtUnreadOnly.value)
        // That first tap was a real Cyan→DT switch, which legitimately raised the « recall to top »
        // signal (#106/#546). Consume it so the assertion below proves the RE-TAPS don't raise it.
        vm.consumeRecallListToTop()

        vm.selectTab(FlagTab.Dt) // re-tap: true → false (« +lus » shown)
        assertFalse(vm.dtUnreadOnly.value)
        vm.selectTab(FlagTab.Dt) // re-tap: false → true
        assertTrue(vm.dtUnreadOnly.value)
        // The re-tap must not move the selected tab nor recall the list to the top (like Cyan).
        assertEquals(FlagTab.Dt, vm.selectedTab.value)
        assertFalse("DT re-tap must not recall the list to the top", vm.recallListToTop.value)
    }

    @Test
    fun `selecting DT from another tab does not toggle the filter`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        vm.selectTab(FlagTab.Red)
        vm.selectTab(FlagTab.Dt) // first tap onto DT: selects, keeps the default unread-only
        assertEquals(FlagTab.Dt, vm.selectedTab.value)
        assertTrue(vm.dtUnreadOnly.value)
    }

    @Test
    fun `dtShowsRead mirrors DT selected and unread filter off`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val vm = viewModel(auth, flags, FakeForumRepository())

        assertFalse("Cyan selected, no suffix", vm.dtShowsRead.value)
        vm.selectTab(FlagTab.Dt)
        assertFalse("DT selected but unread-only on → no « +lus »", vm.dtShowsRead.value)
        vm.selectTab(FlagTab.Dt) // re-tap → +lus
        assertTrue("DT selected with read shown → « +lus »", vm.dtShowsRead.value)
    }

    @Test
    fun `dtDisplayState keeps only unread inbox rows by default and excludes orphans and read rows`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(
                    stubSummary(threadId = 10, isMultiRecipient = true, hasUnread = true),
                    stubSummary(threadId = 20, isMultiRecipient = true, hasUnread = false),
                ),
            ),
        )
        // An orphan storage-only entry (state unknown) must be excluded by the unread filter.
        val mpStorage = FakeMpStorageRepository(
            result = MpStorageResult.Found(
                mpDoc(MpStorageFlagEntry(threadId = 99, page = 2, numreponse = null, uri = null)),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages, mpStorage = mpStorage)

        vm.onDtTabOpened()
        advanceUntilIdle()
        // Default unread-only keeps only the unread inbox row (10); the read row (20) and the
        // storage-only orphan (99, read state unknown) are excluded.
        assertEquals(
            listOf(10),
            (vm.dtDisplayState.value as DtListUiState.Content).items.map { it.threadId },
        )
        // The raw union still carries all three (10 + 20 + orphan 99).
        assertEquals(
            listOf(10, 20, 99),
            (vm.dtListState.value as DtListUiState.Content).items.map { it.threadId },
        )

        // Reveal « +lus »: switch onto DT (a plain selection, no toggle) then re-tap to turn the
        // unread filter OFF — the full union is then displayed.
        vm.selectTab(FlagTab.Dt)
        vm.selectTab(FlagTab.Dt)
        advanceUntilIdle()
        assertFalse(vm.dtUnreadOnly.value)
        assertEquals(
            listOf(10, 20, 99),
            (vm.dtDisplayState.value as DtListUiState.Content).items.map { it.threadId },
        )
    }

    @Test
    fun `dtDisplayState is NoUnread when the union is non-empty but nothing is unread`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(stubSummary(threadId = 10, isMultiRecipient = true, hasUnread = false)),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtDisplayState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            // Union is non-empty (one read conversation) but the unread filter hides it → NoUnread,
            // distinct from Empty (which means no conversation at all).
            assertEquals(DtListUiState.NoUnread, awaitItem())
            assertTrue("the raw union still holds the read conversation", vm.dtListState.value is DtListUiState.Content)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dtDisplayState stays Empty (not NoUnread) when there is no conversation at all`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(inboxPage(stubSummary(threadId = 20, isMultiRecipient = false))),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.dtDisplayState.test {
            assertEquals(DtListUiState.Loading, awaitItem())
            vm.onDtTabOpened()
            assertEquals(DtListUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `refreshDt toggles dtIsRefreshing around the round-trip without blanking the content to Loading`() =
        runTest {
            val flags = FakeFlagRepository()
            val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
            val messages = FakeMessagesRepository(
                inboxResult = Result.success(
                    inboxPage(stubSummary(threadId = 10, isMultiRecipient = true, hasUnread = true)),
                ),
            )
            messages.blockInboxUntil = kotlinx.coroutines.CompletableDeferred()
            val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

            // Cold open first (ungated), so there is content to keep during the refresh.
            messages.blockInboxUntil!!.complete(Unit)
            vm.onDtTabOpened()
            assertTrue(vm.dtListState.value is DtListUiState.Content)

            // Now gate the refresh round-trip so the in-flight indicator is observable.
            messages.blockInboxUntil = kotlinx.coroutines.CompletableDeferred()
            vm.refreshDt()
            assertTrue("dtIsRefreshing rises during the refresh", vm.dtIsRefreshing.value)
            assertTrue(
                "the content must NOT blank to Loading during a refresh (#225 pattern)",
                vm.dtListState.value is DtListUiState.Content,
            )

            messages.blockInboxUntil!!.complete(Unit)
            advanceUntilIdle()
            assertFalse("dtIsRefreshing falls after the round-trip", vm.dtIsRefreshing.value)
            assertTrue(vm.dtListState.value is DtListUiState.Content)
        }

    @Test
    fun `account switch keeps the DT unread filter but clears the refresh indicator`() = runTest {
        val flags = FakeFlagRepository()
        val auth = FakeAuthRepository(AuthState.Authenticated("xaat"), flagRepository = flags)
        val messages = FakeMessagesRepository(
            inboxResult = Result.success(
                inboxPage(stubSummary(threadId = 10, isMultiRecipient = true, hasUnread = true)),
            ),
        )
        val vm = viewModel(auth, flags, FakeForumRepository(), messages = messages)

        vm.selectTab(FlagTab.Dt)
        vm.selectTab(FlagTab.Dt) // re-tap → +lus (unread filter off)
        assertFalse(vm.dtUnreadOnly.value)

        auth.emit(AuthState.Authenticated("other")) // account switch → resetDtState

        assertEquals(DtListUiState.Loading, vm.dtListState.value)
        assertFalse("the refresh indicator is reset on account switch", vm.dtIsRefreshing.value)
        assertFalse("the « +lus » display preference survives the account switch", vm.dtUnreadOnly.value)
    }

    // ──────────────────────────────────────────────────────────────────────
    // Badge regression (beta) — DT row unread-on-open reported to the host
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun `dtRowWasUnread reports the inbox-backed conversation unread state for the badge`() {
        // The DT-open handler never fed unreadOnOpenThreadIds, so the MP badge never decremented when a
        // MultiMP was read via the DT tab. dtRowWasUnread is the value DtRow now passes to onOpenMultiMp;
        // for an inbox-backed row it MUST mirror the conversation's live hasUnread (like onOpenThread).
        val unread = DtListItem.InboxBacked(
            conversation = stubSummary(threadId = 1, isMultiRecipient = true, hasUnread = true),
            resumePage = null,
        )
        val read = DtListItem.InboxBacked(
            conversation = stubSummary(threadId = 2, isMultiRecipient = true, hasUnread = false),
            resumePage = 3,
        )
        assertTrue("an unread inbox-backed DT row must report wasUnread = true", dtRowWasUnread(unread))
        assertFalse("a read inbox-backed DT row must report wasUnread = false", dtRowWasUnread(read))
    }

    @Test
    fun `dtRowWasUnread reports false for a storage-only orphan whose read state is unknown`() {
        // An orphan off inbox PAGE 1 has no known read/unread state, so the badge must never be
        // speculatively decremented for it: wasUnread = false.
        val orphan = DtListItem.StorageOnly(threadId = 9, resumePage = 4, numreponse = null)
        assertFalse("a storage-only DT row must report wasUnread = false", dtRowWasUnread(orphan))
    }

    private fun stubSummary(
        threadId: Int,
        isMultiRecipient: Boolean,
        hasUnread: Boolean = false,
        lastPage: Int = 1,
    ): PrivateMessageSummary = PrivateMessageSummary(
        threadId = threadId,
        correspondent = if (isMultiRecipient) "" else "someone",
        subject = "Conversation $threadId",
        date = Instant.parse("2026-06-18T12:00:00Z"),
        hasUnread = hasUnread,
        isMultiRecipient = isMultiRecipient,
        lastPage = lastPage,
    )

    private fun inboxPage(vararg items: PrivateMessageSummary): PrivateMessageListPage =
        PrivateMessageListPage(page = 1, totalPages = 1, items = items.toList())

    private fun mpDoc(vararg entries: MpStorageFlagEntry): MpStorageDocument =
        MpStorageDocument(sourceName = "DTCloud", mpFlags = entries.toList(), rawEnvelope = "{}")

    /** Mutable [Clock] for the #378 throttle tests — `now` is advanced by hand. */
    private class SteppingClock(start: Instant) : Clock() {
        var now: Instant = start
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    @Suppress("LongParameterList") // test fake-builder: each repo fake is an independent collaborator
    private fun viewModel(
        auth: FakeAuthRepository,
        flags: FakeFlagRepository,
        forum: FakeForumRepository,
        prefs: FakeUserPreferencesRepository = FakeUserPreferencesRepository(),
        messages: FakeMessagesRepository = FakeMessagesRepository(),
        mpStorage: FakeMpStorageRepository = FakeMpStorageRepository(),
    ): FlagsViewModel = FlagsViewModel(auth, flags, forum, prefs, messages, mpStorage, fixedClock)

    /** Builds a ViewModel with a custom [clock] for the #378 throttle tests; everything else is a
     * fresh default fake. */
    private fun viewModelWithClock(
        auth: FakeAuthRepository,
        flags: FakeFlagRepository,
        clock: Clock,
    ): FlagsViewModel = FlagsViewModel(
        auth,
        flags,
        FakeForumRepository(),
        FakeUserPreferencesRepository(),
        FakeMessagesRepository(),
        FakeMpStorageRepository(),
        clock,
    )

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

        // #455 — not exercised by FlagsViewModel (the Drapeaux tab uses FlagRepository).
        override suspend fun getFlagFilteredTopics(
            cat: Int,
            subcat: Int?,
            bucket: FlagFilterBucket,
        ): ForumResult<TopicListPage> = ForumResult.Failure(UnsupportedOperationException())
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

        override fun observeTopicPageFabs(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setTopicPageFabs(enabled: Boolean) = Unit

        override fun observeMpUnreadBadge(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setMpUnreadBadge(enabled: Boolean) = Unit

        override fun observeTopicPollsExpanded(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicPollsExpanded(enabled: Boolean) = Unit

        override fun observeTopicSignatures(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setTopicSignatures(enabled: Boolean) = Unit

        override fun observeFoldLongQuotes(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setFoldLongQuotes(enabled: Boolean) = Unit

        override fun observeShowScrollbar(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setShowScrollbar(enabled: Boolean) = Unit

        override fun observeStartScreen(): Flow<StartScreenPreference> =
            MutableStateFlow(StartScreenPreference())

        override suspend fun setStartScreen(preference: StartScreenPreference) = Unit

        // #459 — upload provider / imgur Client-ID are irrelevant to FlagsViewModel; default stubs.
        override fun observeUploadProvider(): Flow<UploadProviderId> =
            MutableStateFlow(UploadProviderId.DIBERIE)

        override suspend fun setUploadProvider(provider: UploadProviderId) = Unit

        override fun observeImgurClientId(): Flow<String> = MutableStateFlow("")

        override suspend fun setImgurClientId(clientId: String) = Unit

        override fun observeEditorImageInsert(): Flow<EditorImageInsert> =
            MutableStateFlow(EditorImageInsert.REDUCED)

        override suspend fun setEditorImageInsert(mode: EditorImageInsert) = Unit

        // #312 — confirm-before-posting is irrelevant to FlagsViewModel; stubbed at its default.
        override fun observeConfirmBeforePosting(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setConfirmBeforePosting(enabled: Boolean) = Unit

        override fun observeShowDtSection(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setShowDtSection(enabled: Boolean) = Unit

        override fun observeSyncPrivateMessagesWriteEnabled(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setSyncPrivateMessagesWriteEnabled(enabled: Boolean) = Unit

        // #378 — writable so the auto-refresh tests can flip the opt-out.
        val flagsAutoRefresh = MutableStateFlow(true)

        override fun observeFlagsAutoRefresh(): Flow<Boolean> = flagsAutoRefresh

        override suspend fun setFlagsAutoRefresh(enabled: Boolean) {
            flagsAutoRefresh.value = enabled
        }

        fun setGroupBy(value: Boolean) {
            groupBy.value = value
        }

        fun setHideRead(value: Boolean) {
            hideRead.value = value
        }

        fun setPerTabOverride(value: Boolean) {
            perTab.value = value
        }

        // #287 — reading display presets are irrelevant to FlagsViewModel; stubbed at defaults.
        override fun observeDisplayDensity(): Flow<DisplayDensity> = MutableStateFlow(DisplayDensity.COMFORT)

        override suspend fun setDisplayDensity(density: DisplayDensity) = Unit

        override fun observeFontScale(): Flow<FontScalePreference> = MutableStateFlow(FontScalePreference.M)

        override suspend fun setFontScale(scale: FontScalePreference) = Unit

        override fun observeDebugBoundsOverlay(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setDebugBoundsOverlay(enabled: Boolean) = Unit

        override fun observeHideSystemNavBar(): Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setHideSystemNavBar(enabled: Boolean) = Unit

        override fun observeImmersiveBackButton(): Flow<Boolean> = MutableStateFlow(true)

        override suspend fun setImmersiveBackButton(enabled: Boolean) = Unit

        override fun observeImmersiveNavBarReveal(): Flow<ImmersiveNavBarReveal> =
            MutableStateFlow(ImmersiveNavBarReveal.MANUAL)

        override suspend fun setImmersiveNavBarReveal(mode: ImmersiveNavBarReveal) = Unit
        override fun observeAccentColor(): Flow<AccentColor> = MutableStateFlow(AccentColor.ROSE)
        override suspend fun setAccentColor(color: AccentColor) = Unit
    }

    /**
     * Fake [MessagesRepository] for the #6 DT tab tests. Only [getPrivateMessageList] is exercised
     * by FlagsViewModel (the DT list source) ; [inboxResult] lets a test seed the inbox page or
     * make the load throw. The unread-count / thread members are stubbed at no-op defaults.
     */
    private class FakeMessagesRepository(
        var inboxResult: Result<PrivateMessageListPage> =
            Result.success(PrivateMessageListPage(page = 1, totalPages = 1, items = emptyList())),
    ) : MessagesRepository {
        var getListCalls: Int = 0
            private set

        /** Records the `page` argument of each [getPrivateMessageList] call (DT must only read page 1). */
        var requestedPages: List<Int> = emptyList()
            private set

        /** When set, gates [getPrivateMessageList] so a test can hold the inbox load in flight (e.g.
         * to fire an account switch and prove the stale result never publishes). */
        var blockInboxUntil: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override fun observeUnreadMpCount(): Flow<Int?> = MutableStateFlow(null)
        override fun requestUnreadRefresh() = Unit
        override fun markThreadRead(threadId: Int) = Unit

        override suspend fun getPrivateMessageList(page: Int): PrivateMessageListPage {
            getListCalls += 1
            requestedPages = requestedPages + page
            blockInboxUntil?.await()
            return inboxResult.getOrThrow()
        }

        override suspend fun getPrivateMessageThread(
            threadId: Int,
            page: Int,
            fallbackCorrespondent: String?,
        ): PrivateMessageThread = PrivateMessageThread(
            threadId = threadId,
            subject = "",
            correspondent = "",
            messages = emptyList(),
            page = page,
            totalPages = 1,
        )
    }

    /**
     * Fake [MpStorageRepository] for the #6 DT tests. [result] seeds the lookup outcome (or a thrown
     * error via [thrown]) so a test can prove the best-effort join tolerates NotFound / Unreadable /
     * a transport failure — the list must still render in every case.
     */
    private class FakeMpStorageRepository(
        var result: MpStorageResult = MpStorageResult.NotFound,
        var thrown: Throwable? = null,
    ) : MpStorageRepository {
        var fetchCalls: Int = 0
            private set

        override suspend fun fetchStorage(): MpStorageResult {
            fetchCalls += 1
            thrown?.let { throw it }
            return result
        }

        // The DT tab only READS MPStorage; the write path (#6, opt-in OFF) is never exercised here, so the
        // fake stubs both write entry points. The default opt-in OFF maps to DisabledByPreference.
        override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
            MpStorageWriteResult.DisabledByPreference

        override suspend fun previewWriteBackFlag(
            entry: MpStorageFlagEntry,
        ): fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview =
            fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview.TargetNotFound
    }
}
