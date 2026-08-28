package fr.forumhfr.redface2.feature.messages

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.author.AuthorRoleRepository
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.domain.blacklist.canonicalizePseudo
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.media.ImageSaveException
import fr.forumhfr.redface2.core.domain.media.PostImageSaver
import fr.forumhfr.redface2.core.domain.media.SavedPostImage
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageThreadPage
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview
import fr.forumhfr.redface2.core.domain.preferences.UserPreferencesRepository
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.AuthorRole
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.advanceUntilIdle
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
class PrivateMessageThreadViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val request = PrivateMessageThreadRequest(
        threadId = 42,
        page = 1,
    )

    @Suppress("LongParameterList") // Test factory : each dep has a default so call-sites stay terse.
    private fun threadViewModel(
        repository: MessagesRepository,
        threadRequest: PrivateMessageThreadRequest = request,
        authRepository: AuthRepository = FakeAuthRepository(),
        userPreferencesRepository: UserPreferencesRepository = userPreferences(),
        blacklistRepository: BlacklistRepository = FakeBlacklistRepository(),
        authorRoleRepository: AuthorRoleRepository = FakeAuthorRoleRepository(),
        readPositionStore: PrivateMessageReadPositionStore = FakeReadPositionStore(),
        mpStorageRepository: MpStorageRepository = FakeMpStorageRepository(),
        writeRepository: PrivateMessageWriteRepository = mockk(relaxed = true),
        postImageSaver: PostImageSaver = mockk(relaxed = true),
    ) = PrivateMessageThreadViewModel(
        request = threadRequest,
        repository = repository,
        authRepository = authRepository,
        userPreferencesRepository = userPreferencesRepository,
        blacklistRepository = blacklistRepository,
        authorRoleRepository = authorRoleRepository,
        readPositionStore = readPositionStore,
        mpStorageRepository = mpStorageRepository,
        writeRepository = writeRepository,
        postImageSaver = postImageSaver,
    )

    private fun userPreferences(
        fullWidthPosts: Flow<Boolean> = MutableStateFlow(false),
        showSignatures: Flow<Boolean> = MutableStateFlow(false),
        egoQuoteEnabled: Flow<Boolean> = MutableStateFlow(true),
        egoPostEnabled: Flow<Boolean> = MutableStateFlow(true),
        showPageFabs: Flow<Boolean> = MutableStateFlow(true),
    ): UserPreferencesRepository = mockk {
        every { observeTopicFullWidthPosts() } returns fullWidthPosts
        every { observeTopicSignatures() } returns showSignatures
        every { observeTopicEgoQuoteEnabled() } returns egoQuoteEnabled
        every { observeTopicEgoPostEnabled() } returns egoPostEnabled
        every { observeTopicPageFabs() } returns showPageFabs
    }

    @Test
    fun `loads the thread on init without private route metadata fallback`() = runTest {
        val repository = mockk<MessagesRepository>()
        val thread = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread)

        val viewModel = threadViewModel(repository)

        val state = viewModel.state.value
        assertTrue(state.mode is PrivateMessageThreadUiState.Mode.Content)
        assertEquals(thread, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        // Route state deliberately excludes subject/correspondent so stale Navigation entries
        // cannot expose private metadata after logout/process restore.
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `staff directory is requested once and merged into loaded content`() = runTest {
        val staffRepository = FakeAuthorRoleRepository(
            staff = mapOf("antp" to AuthorRole.SUPER_ADMIN),
        )
        val viewModel = threadViewModel(
            repository = loadedRepository(),
            authorRoleRepository = staffRepository,
        )

        val content = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(mapOf("antp" to AuthorRole.SUPER_ADMIN), content.staffByPseudo)
        assertEquals(1, staffRepository.calls)
    }

    @Test
    fun `a suspended staff lookup never blocks the private thread load`() = runTest {
        val gate = CompletableDeferred<Map<String, AuthorRole>>()
        val viewModel = threadViewModel(
            repository = loadedRepository(),
            authorRoleRepository = FakeAuthorRoleRepository(gate = gate),
        )

        val contentBeforeStaff = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(emptyMap<String, AuthorRole>(), contentBeforeStaff.staffByPseudo)

        gate.complete(emptyMap())
        advanceUntilIdle()
    }

    @Test
    fun `a late staff success is fused into content without reloading the thread`() = runTest {
        val repository = loadedRepository()
        val gate = CompletableDeferred<Map<String, AuthorRole>>()
        val viewModel = threadViewModel(
            repository = repository,
            authorRoleRepository = FakeAuthorRoleRepository(gate = gate),
        )

        gate.complete(mapOf("joce" to AuthorRole.ARCHITECT))
        advanceUntilIdle()

        val content = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(mapOf("joce" to AuthorRole.ARCHITECT), content.staffByPseudo)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `a staff lookup failure is silent and leaves content loaded`() = runTest {
        val viewModel = threadViewModel(
            repository = loadedRepository(),
            authorRoleRepository = FakeAuthorRoleRepository(error = IOException("staff offline")),
        )

        val content = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(emptyMap<String, AuthorRole>(), content.staffByPseudo)
    }

    @Test
    fun `an account switch does not refetch the global staff directory`() = runTest {
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val staffRepository = FakeAuthorRoleRepository(
            staff = mapOf("ernestor" to AuthorRole.MODERATOR),
        )
        threadViewModel(
            repository = loadedRepository(),
            authRepository = authRepository,
            authorRoleRepository = staffRepository,
        )

        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        assertEquals(1, staffRepository.calls)
    }

    @Test
    fun `manual refresh retries the best effort staff lookup`() = runTest {
        val staffRepository = FakeAuthorRoleRepository()
        val viewModel = threadViewModel(
            repository = loadedRepository(),
            authorRoleRepository = staffRepository,
        )

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(2, staffRepository.calls)
    }

    @Test
    fun `anonymous state does not fetch the private thread`() = runTest {
        val repository = mockk<MessagesRepository>()

        val viewModel = PrivateMessageThreadViewModel(
            request = request,
            repository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            userPreferencesRepository = userPreferences(),
            blacklistRepository = FakeBlacklistRepository(),
            authorRoleRepository = FakeAuthorRoleRepository(),
            readPositionStore = FakeReadPositionStore(),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
            postImageSaver = mockk(relaxed = true),
        )

        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) {
            repository.getPrivateMessageThread(any(), any(), any())
        }
    }

    @Test
    fun `reading preferences update presentation without refetching the thread`() = runTest {
        val repository = loadedRepository()
        val fullWidthPosts = MutableStateFlow(false)
        val showSignatures = MutableStateFlow(false)
        val viewModel = threadViewModel(
            repository = repository,
            userPreferencesRepository = userPreferences(fullWidthPosts, showSignatures),
        )

        assertFalse(viewModel.state.value.fullWidthPosts)
        assertFalse(viewModel.state.value.showSignatures)

        fullWidthPosts.value = true
        showSignatures.value = true
        advanceUntilIdle()

        assertTrue(viewModel.state.value.fullWidthPosts)
        assertTrue(viewModel.state.value.showSignatures)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `historical page FAB preference updates MP chrome without refetching the thread`() = runTest {
        val repository = loadedRepository()
        val showPageFabs = MutableStateFlow(true)
        val viewModel = threadViewModel(
            repository = repository,
            userPreferencesRepository = userPreferences(showPageFabs = showPageFabs),
        )

        assertTrue(viewModel.state.value.showPageFabs)
        showPageFabs.value = false
        advanceUntilIdle()

        assertFalse(viewModel.state.value.showPageFabs)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `ego preferences are independent render-only flows that never refetch the thread`() = runTest {
        // #1050 — the two #874 Ego markers are deliberately independent: flipping one leaves the
        // other untouched, and neither flip triggers a private network request (render-only).
        val repository = loadedRepository()
        val egoQuote = MutableStateFlow(true)
        val egoPost = MutableStateFlow(true)
        val viewModel = threadViewModel(
            repository = repository,
            userPreferencesRepository = userPreferences(egoQuoteEnabled = egoQuote, egoPostEnabled = egoPost),
        )

        assertTrue(viewModel.state.value.egoQuoteEnabled)
        assertTrue(viewModel.state.value.egoPostEnabled)

        egoQuote.value = false
        advanceUntilIdle()
        assertFalse(viewModel.state.value.egoQuoteEnabled)
        assertTrue("EgoPost must not follow the EgoQuote toggle", viewModel.state.value.egoPostEnabled)

        egoPost.value = false
        advanceUntilIdle()
        assertFalse(viewModel.state.value.egoPostEnabled)

        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `blacklist updates both message and quote masks live without refetching`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(
            thread(
                page = 1,
                totalPages = 1,
                messages = listOf(post(7, author = "Alice"), post(8, author = "Bob")),
            ),
        )
        val blacklist = FakeBlacklistRepository()
        val viewModel = threadViewModel(repository, blacklistRepository = blacklist)

        val initial = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(emptySet<Int>(), initial.hiddenNumreponses)
        assertEquals(emptySet<String>(), initial.blockedQuoteAuthors)

        blacklist.emit(setOf(canonicalizePseudo(" Alice ")))
        advanceUntilIdle()

        val blocked = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(setOf(7), blocked.hiddenNumreponses)
        assertEquals(setOf("alice"), blocked.blockedQuoteAuthors)
        assertEquals(
            "filtering must not remove or reorder messages",
            listOf(7, 8),
            blocked.thread.messages.map { it.numreponse },
        )

        blacklist.emit(emptySet())
        advanceUntilIdle()

        val unblocked = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(emptySet<Int>(), unblocked.hiddenNumreponses)
        assertEquals(emptySet<String>(), unblocked.blockedQuoteAuthors)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `menu block and unblock update both masks immediately without refetching`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(
            thread(
                page = 1,
                totalPages = 1,
                messages = listOf(post(7, author = "Alice"), post(8, author = "Bob")),
            ),
        )
        val blacklist = FakeBlacklistRepository()
        val viewModel = threadViewModel(repository, blacklistRepository = blacklist)

        viewModel.setAuthorBlocked(author = " Alice ", blocked = true)
        advanceUntilIdle()

        val blocked = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(setOf(7), blocked.hiddenNumreponses)
        assertEquals(setOf("alice"), blocked.blockedQuoteAuthors)

        viewModel.setAuthorBlocked(author = "ALICE", blocked = false)
        advanceUntilIdle()

        val restored = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(emptySet<Int>(), restored.hiddenNumreponses)
        assertEquals(emptySet<String>(), restored.blockedQuoteAuthors)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `the session pseudo is exposed for the Ego markers and follows an account switch`() = runTest {
        // #1050 — the Ego markers derive from the session pseudo in the STATE, never from the
        // cached Post.isOwnPost bit; an A → B switch must therefore re-expose B's pseudo. This
        // test only covers the happy path (B's page lands immediately); the purge of A's private
        // state across the switch is pinned by the dedicated test below (gate Sol).
        val repository = loadedRepository()
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))

        val viewModel = threadViewModel(
            repository = repository,
            authRepository = authRepository,
        )
        assertEquals("alice", viewModel.state.value.connectedPseudo)

        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()
        assertEquals("bob", viewModel.state.value.connectedPseudo)
    }

    @Test
    fun `logout purges the session pseudo while the render-only preferences survive`() = runTest {
        // #1050 — the session pseudo is identity data: clearPrivateState drops it (both Ego
        // markers go dark on the logged-out screen), while the four render-only preferences are
        // carried across the reset like in #1050 PR 1 (they are not session data).
        val repository = loadedRepository()
        val authRepository = FakeAuthRepository()
        val egoQuote = MutableStateFlow(false)
        val viewModel = threadViewModel(
            repository = repository,
            authRepository = authRepository,
            userPreferencesRepository = userPreferences(egoQuoteEnabled = egoQuote),
        )
        assertEquals("xaat", viewModel.state.value.connectedPseudo)
        assertFalse(viewModel.state.value.egoQuoteEnabled)

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, state.mode)
        assertEquals(null, state.connectedPseudo)
        assertFalse("the disabled EgoQuote preference must survive the logout reset", state.egoQuoteEnabled)
        assertTrue("the enabled EgoPost preference must survive the logout reset", state.egoPostEnabled)
    }

    @Test
    fun `a direct account switch purges A's content and roster even when B's fetch never lands`() = runTest {
        // Gate Sol (#1050 PR 2) — architecture.md requires the private state to be purged on a
        // SESSION CHANGE, not only at logout. Without the purge, the keep-content reload keeps A's
        // conversation on screen while B's fetch is in flight — indefinitely if it fails — with
        // the roster cache still serving A's member list, and (since this PR) the Ego markers
        // resolving against B's pseudo over A's messages. B's fetch is therefore parked on a gate:
        // every assertion below must hold BEFORE any page of B's session ever lands.
        val repository = mockk<MessagesRepository>()
        val pageForA = thread(
            page = 1,
            totalPages = 1,
            messages = listOf(post(7, author = "Alice")),
        )
        val gate = CompletableDeferred<PrivateMessageThread>()
        var calls = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } answers {
            flow {
                emit(networkPage(if (calls++ == 0) pageForA else gate.await()))
            }
        }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val write = mockk<PrivateMessageWriteRepository>()
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = "bob, carol")
        val egoQuote = MutableStateFlow(false)
        val blacklist = FakeBlacklistRepository(setOf("alice"))
        val viewModel = threadViewModel(
            repository = repository,
            authRepository = authRepository,
            userPreferencesRepository = userPreferences(egoQuoteEnabled = egoQuote),
            blacklistRepository = blacklist,
            writeRepository = write,
        )
        // A's session: content on screen AND the roster sheet loaded (its form is now cached).
        viewModel.openRoster()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)
        assertTrue(viewModel.state.value.roster is PrivateMessageThreadUiState.Roster.Loaded)
        val contentForA = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(setOf(7), contentForA.hiddenNumreponses)
        assertEquals(1, blacklist.subscriptions)

        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        // B's page has NOT landed: nothing of A's session may still be visible — the switch reset
        // through the logout path, landing on Loading (never the login placeholder mid-switch).
        val switched = viewModel.state.value
        assertEquals(PrivateMessageThreadUiState.Mode.Loading, switched.mode)
        assertEquals(PrivateMessageThreadUiState.Roster.Hidden, switched.roster)
        assertEquals("bob", switched.connectedPseudo)
        assertEquals("the account switch must resubscribe the account-owned blacklist", 2, blacklist.subscriptions)
        assertFalse("the disabled EgoQuote preference must survive the switch", switched.egoQuoteEnabled)
        assertTrue("the enabled EgoPost preference must survive the switch", switched.egoPostEnabled)

        // B's fetch then FAILS: the screen shows an Error — A's conversation never resurfaces.
        gate.completeExceptionally(IOException("bob's fetch failed"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals(PrivateMessageThreadUiState.Roster.Hidden, viewModel.state.value.roster)

        // A's cached roster form was purged too: re-opening fetches B's form instead of serving A's.
        viewModel.openRoster()
        advanceUntilIdle()
        coVerify(exactly = 2) { write.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns failure(IOException("offline"))

        val viewModel = threadViewModel(repository)

        // #316: the Error mode carries NO raw throwable message (privacy — it can embed the private
        // conversation URL). The only detail is the #324 type-derived kind (safe closed enum).
        val mode = viewModel.state.value.mode
        assertTrue(mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals(HfrErrorKind.Network, (mode as PrivateMessageThreadUiState.Mode.Error).kind)
    }

    @Test
    fun `a zero-emission initial load surfaces a retryable generic Error`() = runTest {
        // #1086 — the repository refuses a response whose session-cache stamp lost the race with
        // invalidation by completing without data. This is not a network failure: the ViewModel
        // maps the empty collection to the privacy-safe generic error, whose existing button retries.
        val repository = mockk<MessagesRepository>()
        val recovered = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns emptyFlow() andThen network(recovered)

        val viewModel = threadViewModel(repository)

        val refused = viewModel.state.value.mode
        assertTrue(refused is PrivateMessageThreadUiState.Mode.Error)
        assertEquals(HfrErrorKind.Other, (refused as PrivateMessageThreadUiState.Mode.Error).kind)

        viewModel.retry()
        advanceUntilIdle()

        val loaded = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(recovered, loaded.thread)
        coVerify(exactly = 2) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `a zero-emission load from a replaced account cannot overwrite the new Loading state`() = runTest {
        // The same empty completion is a deliberate refusal when A was replaced by B. The auth
        // collector cancels A, purges its state and starts B's load; A must not pose Error over B's
        // spinner while B is still in flight.
        val repository = mockk<MessagesRepository>()
        val pageForB = CompletableDeferred<PrivateMessageThread>()
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        var calls = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } answers {
            if (calls++ == 0) {
                flow {
                    authRepository.emit(AuthState.Authenticated("bob"))
                    // Alice's response is refused: complete without an emission.
                }
            } else {
                flow { emit(networkPage(pageForB.await())) }
            }
        }

        val viewModel = threadViewModel(repository, authRepository = authRepository)
        advanceUntilIdle()

        val switched = viewModel.state.value
        assertEquals(PrivateMessageThreadUiState.Mode.Loading, switched.mode)
        assertEquals("bob", switched.connectedPseudo)

        pageForB.complete(thread(page = 1, totalPages = 1))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)
    }

    @Test
    fun `surfaces an HFR 5xx load failure with the ServerDown kind`() = runTest {
        // #324 — an HFR outage must be distinguishable from a network cut on a conversation,
        // still without any raw message (the kind is derived from the exception TYPE only —
        // never from a string that could embed forum2.php?cat=prive&post=<id>).
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns failure(HfrServerException(code = 500, url = "https://forum.hardware.fr/forum2.php"))

        val viewModel = threadViewModel(repository)

        val mode = viewModel.state.value.mode
        assertTrue(mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals(HfrErrorKind.ServerDown, (mode as PrivateMessageThreadUiState.Mode.Error).kind)
    }

    @Test
    fun `selectPage loads the requested page`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2))

        val viewModel = threadViewModel(repository)
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertTrue(state.canGoPrevious)
    }

    @Test
    fun `selectPage ignores current and out-of-bounds targets`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 3))

        val viewModel = threadViewModel(repository)
        viewModel.selectPage(1)
        viewModel.selectPage(0)
        viewModel.selectPage(4)

        assertEquals(1, viewModel.state.value.page)
        coVerify(exactly = 0) {
            repository.getPrivateMessageThread(
                threadId = 42,
                page = match { it != 1 },
                fallbackCorrespondent = null,
            )
        }
    }

    @Test
    fun `A to B to A restores exact index and offset while an unvisited page lands at top`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        val pageOneAnchor = PrivateMessageScrollAnchor(index = 7, offset = 42)
        viewModel.selectPage(2, departureAnchor = pageOneAnchor)

        val firstPageTwoLanding = viewModel.state.value.pageLanding
        assertTrue("an unvisited page must not invent an anchor", firstPageTwoLanding is PrivateMessagePageLanding.Top)
        viewModel.acknowledgePageLanding(requireNotNull(firstPageTwoLanding))

        viewModel.selectPage(
            page = 1,
            departureAnchor = PrivateMessageScrollAnchor(index = 3, offset = 19),
        )

        val restored = viewModel.state.value.pageLanding
        assertTrue(restored is PrivateMessagePageLanding.Anchor)
        assertEquals(pageOneAnchor, (restored as PrivateMessagePageLanding.Anchor).anchor)
    }

    @Test
    fun `cache then network retains one anchor landing until exact acknowledgement`() = runTest {
        val repository = mockk<MessagesRepository>()
        val networkGate = CompletableDeferred<Unit>()
        var pageTwoLoads = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } answers {
            if (pageTwoLoads++ == 0) {
                network(thread(page = 2, totalPages = 2))
            } else {
                flow {
                    val page = thread(page = 2, totalPages = 2)
                    emit(PrivateMessageThreadPage(page, PrivateMessageThreadPage.Source.SESSION_CACHE))
                    networkGate.await()
                    emit(networkPage(page))
                }
            }
        }
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(2)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        val anchor = PrivateMessageScrollAnchor(index = 5, offset = 27)
        viewModel.selectPage(1, departureAnchor = anchor)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.selectPage(2)
        val cacheLanding = requireNotNull(viewModel.state.value.pageLanding)
        assertEquals(anchor, (cacheLanding as PrivateMessagePageLanding.Anchor).anchor)

        networkGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            "network revalidation must retain the exact same one-shot landing",
            cacheLanding,
            viewModel.state.value.pageLanding,
        )
        viewModel.acknowledgePageLanding(cacheLanding)
        assertNull(viewModel.state.value.pageLanding)
    }

    @Test
    fun `A to B to C rejects a saved B anchor from the superseded load`() = runTest {
        val repository = mockk<MessagesRepository>()
        val stalePageTwo = CompletableDeferred<PrivateMessageThread>()
        var pageTwoLoads = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 3))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } answers {
            if (pageTwoLoads++ == 0) {
                network(thread(page = 2, totalPages = 3))
            } else {
                flow { emit(networkPage(stalePageTwo.await())) }
            }
        }
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 3))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(2)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(
            page = 1,
            departureAnchor = PrivateMessageScrollAnchor(index = 6, offset = 28),
        )
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.selectPage(2)
        viewModel.selectPage(3)
        stalePageTwo.complete(thread(page = 2, totalPages = 3))
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.page)
        assertTrue(
            "the current C owner keeps its Top; the late B anchor is rejected",
            viewModel.state.value.pageLanding is PrivateMessagePageLanding.Top,
        )
    }

    @Test
    fun `cited landing wins over a saved anchor without deleting that anchor`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 202
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2, messages = listOf(post(101))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2, messages = listOf(post(target))))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(2)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        val pageTwoAnchor = PrivateMessageScrollAnchor(index = 4, offset = 31)
        viewModel.selectPage(1, departureAnchor = pageTwoAnchor)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 2, numreponse = target)
        val cited = requireNotNull(viewModel.state.value.pageLanding)
        assertTrue(cited is PrivateMessagePageLanding.CitedMessage)
        viewModel.acknowledgePageLanding(cited)

        viewModel.selectPage(1)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(2)
        val ordinaryReturn = viewModel.state.value.pageLanding
        assertTrue(ordinaryReturn is PrivateMessagePageLanding.Anchor)
        assertEquals(pageTwoAnchor, (ordinaryReturn as PrivateMessagePageLanding.Anchor).anchor)
    }

    @Test
    fun `refresh stays immobile and submit event refetches once without dropping anchors`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        val anchor = PrivateMessageScrollAnchor(index = 6, offset = 11)
        viewModel.reportPageAnchor(anchor)

        viewModel.refresh()
        assertNull("same-page refresh must not arm a scroll", viewModel.state.value.pageLanding)

        val submit = PrivateMessageSubmitResult(eventId = 17L, page = 1)
        viewModel.applySubmitResult(submit)
        viewModel.applySubmitResult(submit)
        assertNull("same-page post-submit refetch stays at the current position", viewModel.state.value.pageLanding)
        coVerify(exactly = 3) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }

        // The event used the retained instance: leaving and returning still resolves the old anchor.
        viewModel.selectPage(2)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(1)
        val restored = viewModel.state.value.pageLanding as PrivateMessagePageLanding.Anchor
        assertEquals(anchor, restored.anchor)
    }

    @Test
    fun `account switch and logout purge every page anchor`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2))
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val viewModel = threadViewModel(repository, authRepository = authRepository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.selectPage(2)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.reportPageAnchor(PrivateMessageScrollAnchor(index = 8, offset = 64))

        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        assertEquals("bob", viewModel.state.value.connectedPseudo)
        assertTrue(
            "Bob's opening page must land at top, never on Alice's saved coordinates",
            viewModel.state.value.pageLanding is PrivateMessagePageLanding.Top,
        )
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))
        viewModel.reportPageAnchor(PrivateMessageScrollAnchor(index = 9, offset = 72))

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        assertNull(viewModel.state.value.pageLanding)

        authRepository.emit(AuthState.Authenticated("carol"))
        advanceUntilIdle()
        assertTrue(
            "Carol's post-logout opening must not restore Bob's coordinates",
            viewModel.state.value.pageLanding is PrivateMessagePageLanding.Top,
        )
    }

    @Test
    fun `cited message on the rendered page lands without loading`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 101
        val networkGate = CompletableDeferred<Unit>()
        val loaded = thread(page = 1, totalPages = 2, messages = listOf(post(target)))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns flow {
            emit(PrivateMessageThreadPage(loaded, PrivateMessageThreadPage.Source.SESSION_CACHE))
            networkGate.await()
            emit(networkPage(loaded))
        }
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 1, numreponse = target)

        val landing = viewModel.state.value.pageLanding
        assertTrue(landing is PrivateMessagePageLanding.CitedMessage)
        assertEquals(target, (landing as PrivateMessagePageLanding.CitedMessage).numreponse)
        viewModel.acknowledgePageLanding(landing)
        // The local jump must not cancel the mandatory revalidation of this cached page, and the
        // terminal emission must not republish the acknowledged visual landing.
        networkGate.complete(Unit)
        advanceUntilIdle()
        val refreshed = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(PrivateMessageThreadPage.Source.NETWORK, refreshed.source)
        assertNull(viewModel.state.value.pageLanding)
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `cross-page cited landing uses cache once and network does not replay it`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 202
        val networkGate = CompletableDeferred<Unit>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2, messages = listOf(post(101))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns flow {
            val cached = thread(page = 2, totalPages = 2, messages = listOf(post(target)))
            emit(PrivateMessageThreadPage(cached, PrivateMessageThreadPage.Source.SESSION_CACHE))
            networkGate.await()
            emit(networkPage(cached))
        }
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 2, numreponse = target)

        val cached = viewModel.state.value.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(PrivateMessageThreadPage.Source.SESSION_CACHE, cached.source)
        val landing = viewModel.state.value.pageLanding
        assertTrue(landing is PrivateMessagePageLanding.CitedMessage)
        assertEquals(2, landing?.page)
        viewModel.acknowledgePageLanding(requireNotNull(landing))

        networkGate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.state.value.pageLanding)
    }

    @Test
    fun `missing target is cleared against the parsed fallback page`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 999
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 99, messages = listOf(post(101))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 99, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 4, messages = listOf(post(301))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 4, fallbackCorrespondent = null)
        } returns network(thread(page = 4, totalPages = 4, messages = listOf(post(target))))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 99, numreponse = target)
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.page)
        val missing = viewModel.state.value.pageLanding
        assertTrue(missing is PrivateMessagePageLanding.CitedMessage)
        assertEquals("the landing is scoped to HFR's parsed fallback", 3, missing?.page)
        // The screen's terminal-missing fallback acknowledges the exact value.
        viewModel.acknowledgePageLanding(requireNotNull(missing))

        // A later unrelated page happens to contain the same numreponse: only its ordinary Top
        // landing may remain; the terminal miss can never resurrect the cited intention.
        viewModel.selectPage(4)
        advanceUntilIdle()
        assertEquals(4, viewModel.state.value.page)
        assertTrue(viewModel.state.value.pageLanding is PrivateMessagePageLanding.Top)
    }

    @Test
    fun `manual page change supersedes an in-flight cited landing`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 202
        val targetGate = CompletableDeferred<PrivateMessageThread>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 3, messages = listOf(post(101))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns flow { emit(networkPage(targetGate.await())) }
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 3, messages = listOf(post(target))))
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 2, numreponse = target)
        viewModel.selectPage(3)
        targetGate.complete(thread(page = 2, totalPages = 3, messages = listOf(post(target))))
        advanceUntilIdle()

        assertEquals(3, viewModel.state.value.page)
        assertTrue(viewModel.state.value.pageLanding is PrivateMessagePageLanding.Top)
    }

    @Test
    fun `account switch clears an in-flight cited landing`() = runTest {
        val repository = mockk<MessagesRepository>()
        val target = 202
        val targetGate = CompletableDeferred<PrivateMessageThread>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2, messages = listOf(post(101))))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns flow { emit(networkPage(targetGate.await())) }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val viewModel = threadViewModel(repository, authRepository = authRepository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.goToCitedMessage(targetPage = 2, numreponse = target)
        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        assertEquals("bob", viewModel.state.value.connectedPseudo)
        val landing = viewModel.state.value.pageLanding
        assertTrue(landing is PrivateMessagePageLanding.Top)
        assertEquals("bob", landing?.account)
    }

    @Test
    fun `foreground prefetch targets only both adjacent pages`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 5))
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } returns Unit
        val viewModel = threadViewModel(
            repository = repository,
            threadRequest = request.copy(page = 3),
        )

        viewModel.setPrefetchActive(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.prefetchPrivateMessageThread(threadId = 42, page = 2) }
        coVerify(exactly = 1) { repository.prefetchPrivateMessageThread(threadId = 42, page = 4) }
        coVerify(exactly = 0) {
            repository.prefetchPrivateMessageThread(
                threadId = 42,
                page = match { it !in setOf(2, 4) },
            )
        }
    }

    @Test
    fun `prefetching a page never creates a reading anchor for it`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 2))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns network(thread(page = 2, totalPages = 2))
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = 1) } returns Unit
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = 2) } returns Unit
        val viewModel = threadViewModel(repository)
        viewModel.acknowledgePageLanding(requireNotNull(viewModel.state.value.pageLanding))

        viewModel.setPrefetchActive(true)
        advanceUntilIdle()
        coVerify(exactly = 1) {
            repository.prefetchPrivateMessageThread(threadId = 42, page = 2)
        }

        viewModel.selectPage(2)
        advanceUntilIdle()

        val firstPrefetchedPageLanding = viewModel.state.value.pageLanding
        assertTrue(
            "a warmed but never displayed page still has the unvisited Top landing",
            firstPrefetchedPageLanding is PrivateMessagePageLanding.Top,
        )
        assertEquals(2, firstPrefetchedPageLanding?.page)
        viewModel.acknowledgePageLanding(requireNotNull(firstPrefetchedPageLanding))
        coVerify(exactly = 1) {
            repository.prefetchPrivateMessageThread(threadId = 42, page = 1)
        }

        viewModel.selectPage(1)
        advanceUntilIdle()

        val prefetchedPageLanding = viewModel.state.value.pageLanding
        assertTrue(
            "visiting a prefetched page must not restore an anchor created by the prefetch",
            prefetchedPageLanding is PrivateMessagePageLanding.Top,
        )
        assertEquals(1, prefetchedPageLanding?.page)
    }

    @Test
    fun `first and last conversation pages prefetch only their existing neighbor`() = runTest {
        val firstRepository = mockk<MessagesRepository>()
        coEvery {
            firstRepository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 3))
        coEvery { firstRepository.prefetchPrivateMessageThread(threadId = 42, page = any()) } returns Unit
        val firstViewModel = threadViewModel(firstRepository)

        firstViewModel.setPrefetchActive(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { firstRepository.prefetchPrivateMessageThread(threadId = 42, page = 2) }
        coVerify(exactly = 0) {
            firstRepository.prefetchPrivateMessageThread(threadId = 42, page = match { it != 2 })
        }

        val lastRepository = mockk<MessagesRepository>()
        coEvery {
            lastRepository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 3))
        coEvery { lastRepository.prefetchPrivateMessageThread(threadId = 42, page = any()) } returns Unit
        val lastViewModel = threadViewModel(
            repository = lastRepository,
            threadRequest = request.copy(page = 3),
        )

        lastViewModel.setPrefetchActive(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { lastRepository.prefetchPrivateMessageThread(threadId = 42, page = 2) }
        coVerify(exactly = 0) {
            lastRepository.prefetchPrivateMessageThread(threadId = 42, page = match { it != 2 })
        }
    }

    @Test
    fun `same landed page is prefetched once across activation and refresh emissions`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 5))
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } returns Unit
        val viewModel = threadViewModel(
            repository = repository,
            threadRequest = request.copy(page = 3),
        )

        viewModel.setPrefetchActive(true)
        viewModel.setPrefetchActive(true)
        viewModel.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.prefetchPrivateMessageThread(threadId = 42, page = 2) }
        coVerify(exactly = 1) { repository.prefetchPrivateMessageThread(threadId = 42, page = 4) }
    }

    @Test
    fun `leaving foreground cancels the group and same page activation does not restart it`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 5))
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } coAnswers {
            val page = secondArg<Int>()
            started += page
            try {
                awaitCancellation()
            } finally {
                cancelled += page
            }
        }
        val viewModel = threadViewModel(
            repository = repository,
            threadRequest = request.copy(page = 3),
        )
        viewModel.setPrefetchActive(true)

        viewModel.setPrefetchActive(false)
        viewModel.setPrefetchActive(true)
        advanceUntilIdle()

        assertEquals(setOf(2, 4), cancelled.toSet())
        assertEquals(1, started.count { it == 2 })
        assertEquals(1, started.count { it == 4 })
    }

    @Test
    fun `page change cancels the previous adjacent prefetch group`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 5))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 4, fallbackCorrespondent = null)
        } returns network(thread(page = 4, totalPages = 5))
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } coAnswers {
            val page = secondArg<Int>()
            started += page
            try {
                awaitCancellation()
            } finally {
                cancelled += page
            }
        }
        val viewModel = threadViewModel(
            repository = repository,
            threadRequest = request.copy(page = 3),
        )
        viewModel.setPrefetchActive(true)
        assertEquals(setOf(2, 4), started.toSet())

        viewModel.selectPage(4)
        advanceUntilIdle()

        assertTrue(cancelled.containsAll(listOf(2, 4)))
        assertTrue(started.containsAll(listOf(3, 5)))
        viewModel.setPrefetchActive(false)
    }

    @Test
    fun `account switch cancels and replaces the previous account prefetch group`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 3, fallbackCorrespondent = null)
        } returns network(thread(page = 3, totalPages = 5))
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } coAnswers {
            val page = secondArg<Int>()
            started += page
            try {
                awaitCancellation()
            } finally {
                cancelled += page
            }
        }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val viewModel = threadViewModel(
            repository = repository,
            threadRequest = request.copy(page = 3),
            authRepository = authRepository,
        )
        viewModel.setPrefetchActive(true)

        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()

        assertTrue(cancelled.containsAll(listOf(2, 4)))
        assertEquals(2, started.count { it == 2 })
        assertEquals(2, started.count { it == 4 })
        viewModel.setPrefetchActive(false)
    }

    @Test
    fun `disk content stays provisional and only network saves private read side effects`() = runTest {
        val repository = mockk<MessagesRepository>()
        val cached = thread(page = 1, totalPages = 2, messages = listOf(post(7)))
        val refreshed = thread(page = 1, totalPages = 2, messages = listOf(post(8)))
        val networkGate = CompletableDeferred<Unit>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns flow {
            emit(PrivateMessageThreadPage(cached, PrivateMessageThreadPage.Source.DISK))
            networkGate.await()
            emit(networkPage(refreshed))
        }
        coEvery { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) } returns Unit
        val store = FakeReadPositionStore()
        val mpStorage = FakeMpStorageRepository()

        val viewModel = threadViewModel(
            repository = repository,
            readPositionStore = store,
            mpStorageRepository = mpStorage,
        )
        viewModel.setPrefetchActive(true)

        val cachedState = viewModel.state.value
        val cachedContent = cachedState.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(cached, cachedContent.thread)
        assertEquals(PrivateMessageThreadPage.Source.DISK, cachedContent.source)
        assertTrue(cachedState.isRefreshing)
        assertEquals(emptyMap<Int, Int>(), store.saved)
        assertEquals(emptyList<MpStorageFlagEntry>(), mpStorage.ifPresentCalls)
        assertNull(cachedContent.networkLoadedThreadOrNull())
        coVerify(exactly = 0) { repository.prefetchPrivateMessageThread(threadId = 42, page = any()) }

        networkGate.complete(Unit)
        advanceUntilIdle()

        val networkState = viewModel.state.value
        val networkContent = networkState.mode as PrivateMessageThreadUiState.Mode.Content
        assertEquals(refreshed, networkContent.thread)
        assertEquals(PrivateMessageThreadPage.Source.NETWORK, networkContent.source)
        assertFalse(networkState.isRefreshing)
        assertEquals(1, store.saved[42])
        assertEquals(1, mpStorage.ifPresentCalls.size)
        assertEquals(refreshed, networkContent.networkLoadedThreadOrNull())
        coVerify(exactly = 1) { repository.prefetchPrivateMessageThread(threadId = 42, page = 2) }
    }

    @Test
    fun `selectPage keeps the displayed page on screen while the next one loads`() = runTest {
        // #351 — in-place pagination prerequisite for the MP swipe: no full-screen spinner on a
        // page change. The previous page stays in Content behind isRefreshing until the new page
        // lands; page/totalPages only advance on success.
        val repository = mockk<MessagesRepository>()
        val pageOne = thread(page = 1, totalPages = 2)
        val pageTwo = thread(page = 2, totalPages = 2)
        val gate = CompletableDeferred<PrivateMessageThread>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(pageOne)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns flow { emit(networkPage(gate.await())) }

        val viewModel = threadViewModel(repository)
        viewModel.selectPage(2)

        // Page 2 is in flight: page 1 is still what the user sees.
        val inFlight = viewModel.state.value
        assertTrue(inFlight.isRefreshing)
        assertEquals(pageOne, (inFlight.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(1, inFlight.page)

        gate.complete(pageTwo)
        advanceUntilIdle()
        val landed = viewModel.state.value
        assertFalse(landed.isRefreshing)
        assertEquals(pageTwo, (landed.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(2, landed.page)
    }

    @Test
    fun `swipe warmth probe delegates with the authenticated account and route thread`() = runTest {
        val repository = loadedRepository()
        every {
            repository.isPrivateMessageThreadPageWarm(
                account = "xaat",
                threadId = 42,
                page = 2,
            )
        } returns true
        val viewModel = threadViewModel(repository)

        assertTrue(viewModel.isPageWarm(2))
        assertFalse(viewModel.isPageWarm(0))
    }

    @Test
    fun `network failure after cold page selection keeps the outgoing content at rest`() = runTest {
        val repository = mockk<MessagesRepository>()
        val pageOne = thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(pageOne)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns failure(IOException("offline"))
        val viewModel = threadViewModel(repository)

        viewModel.selectPage(2)

        val failed = viewModel.state.value
        assertFalse(failed.isRefreshing)
        assertEquals(1, failed.page)
        assertEquals(pageOne, (failed.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(PrivateMessageThreadEffect.RefreshFailed, viewModel.effects.first())
    }

    @Test
    fun `refresh re-fetches the displayed page in place`() = runTest {
        val repository = mockk<MessagesRepository>()
        val first = thread(page = 1, totalPages = 1)
        val updated = thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(first) andThen network(updated)

        val viewModel = threadViewModel(repository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        assertEquals(updated, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(2, state.totalPages)
        coVerify(exactly = 2) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `refresh failure keeps the displayed page and emits RefreshFailed`() = runTest {
        // #351 — a keep-content load failure must NOT swap a readable conversation for the Error
        // placeholder: the page on screen is still valid. The screen gets a one-shot Toast effect.
        val repository = mockk<MessagesRepository>()
        val pageOne = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(pageOne) andThen failure(IOException("offline"))

        val viewModel = threadViewModel(repository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        assertEquals(pageOne, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(PrivateMessageThreadEffect.RefreshFailed, viewModel.effects.first())
    }

    @Test
    fun `a successful image save emits ImageSaved and forwards the URL to the saver`() = runTest {
        val repository = loadedRepository()
        val saver = FakePostImageSaver { SavedPostImage(displayName = "vacances.png") }
        val viewModel = threadViewModel(repository, postImageSaver = saver)

        // The save completes before a collector attaches: the buffered ViewModel effect survives
        // the sheet's immediate dismissal and is still delivered to the screen collector.
        viewModel.saveImage("https://images.example/vacances.png")
        viewModel.effects.test {
            assertEquals(PrivateMessageThreadEffect.ImageSaved, awaitItem())
        }
        assertEquals(listOf("https://images.example/vacances.png"), saver.requests)
    }

    @Test
    fun `an image fetch failure emits ImageSaveFailedFetch`() = runTest {
        val viewModel = threadViewModel(
            loadedRepository(),
            postImageSaver = FakePostImageSaver { throw ImageSaveException.Fetch() },
        )

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/gone.png")
            assertEquals(PrivateMessageThreadEffect.ImageSaveFailedFetch, awaitItem())
        }
    }

    @Test
    fun `an image storage failure emits ImageSaveFailedStorage`() = runTest {
        val viewModel = threadViewModel(
            loadedRepository(),
            postImageSaver = FakePostImageSaver { throw ImageSaveException.Storage() },
        )

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/full-disk.png")
            assertEquals(PrivateMessageThreadEffect.ImageSaveFailedStorage, awaitItem())
        }
    }

    @Test
    fun `an oversized image emits ImageSaveFailedTooLarge`() = runTest {
        val viewModel = threadViewModel(
            loadedRepository(),
            postImageSaver = FakePostImageSaver {
                throw ImageSaveException.TooLarge(maxBytes = 1L)
            },
        )

        viewModel.effects.test {
            viewModel.saveImage("https://images.example/huge.png")
            assertEquals(PrivateMessageThreadEffect.ImageSaveFailedTooLarge, awaitItem())
        }
    }

    @Test
    fun `refresh is a no-op without loaded content`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns failure(IOException("offline"))

        val viewModel = threadViewModel(repository)
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Error)
        viewModel.refresh()

        // Initial load failed → Error mode; refresh must not fire a hidden reload (Retry is the
        // explicit path out of Error).
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `logout clears private thread content and login reloads it`() = runTest {
        val repository = mockk<MessagesRepository>()
        val authRepository = FakeAuthRepository()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))

        val viewModel = threadViewModel(
            repository = repository,
            authRepository = authRepository,
        )
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)

        authRepository.emit(AuthState.Anonymous)
        advanceUntilIdle()
        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)

        authRepository.emit(AuthState.Authenticated("other"))
        advanceUntilIdle()
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)
    }

    @Test
    fun `opening resumes from the saved position when it is past the route page (#430)`() = runTest {
        // Process-death restoration: the route is frozen on the page the conversation was opened
        // on, the local store remembers the page actually displayed last — the store wins.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 7, fallbackCorrespondent = null)
        } returns network(thread(page = 7, totalPages = 9))

        PrivateMessageThreadViewModel(
            request = request,
            repository = repository,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = userPreferences(),
            blacklistRepository = FakeBlacklistRepository(),
            authorRoleRepository = FakeAuthorRoleRepository(),
            readPositionStore = FakeReadPositionStore(initial = mapOf(42 to 7)),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
            postImageSaver = mockk(relaxed = true),
        )

        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 7, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `opening prefers a route page past the saved position (#430)`() = runTest {
        // The conversation grew since the last visit: the inbox's fresh last-page link (carried
        // by the route) is further than the stale resume point — new messages win.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 9, fallbackCorrespondent = null)
        } returns network(thread(page = 9, totalPages = 9))

        PrivateMessageThreadViewModel(
            request = PrivateMessageThreadRequest(threadId = 42, page = 9),
            repository = repository,
            authRepository = FakeAuthRepository(),
            userPreferencesRepository = userPreferences(),
            blacklistRepository = FakeBlacklistRepository(),
            authorRoleRepository = FakeAuthorRoleRepository(),
            readPositionStore = FakeReadPositionStore(initial = mapOf(42 to 3)),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
            postImageSaver = mockk(relaxed = true),
        )

        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 9, fallbackCorrespondent = null)
        }
    }

    @Test
    fun `a landed page is saved as the reading position (#430)`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 9))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 5, fallbackCorrespondent = null)
        } returns network(thread(page = 5, totalPages = 9))
        val store = FakeReadPositionStore()

        val viewModel =
            PrivateMessageThreadViewModel(
                request = request,
                repository = repository,
                authRepository = FakeAuthRepository(),
                userPreferencesRepository = userPreferences(),
                blacklistRepository = FakeBlacklistRepository(),
                authorRoleRepository = FakeAuthorRoleRepository(),
                readPositionStore = store,
                mpStorageRepository = FakeMpStorageRepository(),
                writeRepository = mockk(relaxed = true),
                postImageSaver = mockk(relaxed = true),
            )
        assertEquals(1, store.saved[42])

        viewModel.selectPage(5)
        advanceUntilIdle()

        assertEquals(5, store.saved[42])
        // #462 — the write is attributed to the reading account, not a re-resolved active user.
        assertEquals("xaat", store.lastSaveOwner)
    }

    @Test
    fun `an account switch seals the previous session's load and position save (#462)`() = runTest {
        // Authenticated(A) → Authenticated(B) without an Anonymous hop: A's in-flight fetch must
        // be cancelled BEFORE the new session's first suspension point, so its result can never
        // pose state — nor save a position — under B (Codex review on PR #462).
        val repository = mockk<MessagesRepository>()
        val gate = CompletableDeferred<PrivateMessageThread>()
        var calls = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } answers {
            flow {
                val loaded = if (calls++ == 0) gate.await() else thread(page = 1, totalPages = 1)
                emit(networkPage(loaded))
            }
        }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val store = FakeReadPositionStore()

        threadViewModel(
            repository = repository,
            authRepository = authRepository,
            readPositionStore = store,
        )
        // alice's fetch is parked on the gate; switch the account.
        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()
        // Releasing alice's gate must be inert — her job was cancelled at the switch.
        gate.complete(thread(page = 3, totalPages = 9))
        advanceUntilIdle()

        assertEquals("only bob's landing may be recorded", 1, store.saved[42])
    }

    @Test
    fun `a stale position save cannot overwrite a newer landing (#462)`() = runTest {
        // save(1) parks on IO while page 5 lands: the newer landing must cancel the stale write
        // (latest-wins serialization), otherwise a delayed upsert would regress the position.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 9))
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 5, fallbackCorrespondent = null)
        } returns network(thread(page = 5, totalPages = 9))
        val store = FakeReadPositionStore()
        val saveGate = CompletableDeferred<Unit>()
        store.blockNextSave = saveGate

        val viewModel = threadViewModel(repository, readPositionStore = store)
        viewModel.selectPage(5)
        advanceUntilIdle()
        // Releasing the parked save(1) must NOT resurrect it after save(5) committed.
        saveGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(5, store.saved[42])
    }

    @Test
    fun `a landed page triggers the update-only MPStorage sync with page, anchor and desktop uri (#597)`() = runTest {
        // The auto trigger fires AFTER the local save, in the same launch. It builds the entry from the
        // landed page + the last post's anchor and the canonical DTCloud desktop uri.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(
            thread(page = 1, totalPages = 3, messages = listOf(post(1000), post(2784595))),
        )
        val mpStorage = FakeMpStorageRepository()

        threadViewModel(repository, mpStorageRepository = mpStorage)
        advanceUntilIdle()

        assertEquals(1, mpStorage.ifPresentCalls.size)
        val entry = mpStorage.ifPresentCalls.single()
        assertEquals(42, entry.threadId)
        assertEquals(1, entry.page)
        // Anchor = the LAST post on the page (the furthest the reader reached).
        assertEquals(2784595, entry.numreponse)
        // Canonical DTCloud desktop uri, with the #t<anchor> fragment matching the page.
        assertEquals("/forum2.php?config=hfr.inc&cat=prive&post=42&page=1#t2784595", entry.uri)
        // C2 — the VM passes the owner it snapshotted for THIS read as expectedPseudo, so the repo can
        // refuse the write if the active account switched across its own suspension points.
        assertEquals("xaat", mpStorage.ifPresentPseudos.single())
    }

    @Test
    fun `the MPStorage sync omits the uri and anchor when the page has no posts (#597)`() = runTest {
        // No anchor available → numreponse null AND uri null, so the update-only path preserves the
        // entry's existing href/uri rather than nulling them (the repository keeps the absent fields).
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1, messages = emptyList()))
        val mpStorage = FakeMpStorageRepository()

        threadViewModel(repository, mpStorageRepository = mpStorage)
        advanceUntilIdle()

        val entry = mpStorage.ifPresentCalls.single()
        assertEquals(null, entry.numreponse)
        assertEquals(null, entry.uri)
    }

    @Test
    fun `a failing MPStorage sync is swallowed and never breaks the local save (#597)`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1, messages = listOf(post(7))))
        val store = FakeReadPositionStore()
        val mpStorage = FakeMpStorageRepository(thrown = IOException("storage write down"))

        val viewModel = threadViewModel(repository, readPositionStore = store, mpStorageRepository = mpStorage)
        advanceUntilIdle()

        // The thrown sync did not break the session: content is loaded and the local position is saved.
        assertTrue(viewModel.state.value.mode is PrivateMessageThreadUiState.Mode.Content)
        assertEquals(1, store.saved[42])
        assertEquals(1, mpStorage.ifPresentCalls.size)
    }

    @Test
    fun `an account switch seals the MPStorage sync to the reading session (#597 + #462)`() = runTest {
        // The sync lives in the same saveJob/session guard as the local save: a save attributed to a
        // session that changed before it fires is dropped before any MPStorage call.
        val repository = mockk<MessagesRepository>()
        val gate = CompletableDeferred<PrivateMessageThread>()
        var calls = 0
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } answers {
            flow {
                val loaded = if (calls++ == 0) {
                    gate.await()
                } else {
                    thread(page = 1, totalPages = 1, messages = listOf(post(9)))
                }
                emit(networkPage(loaded))
            }
        }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val mpStorage = FakeMpStorageRepository()

        threadViewModel(
            repository = repository,
            authRepository = authRepository,
            mpStorageRepository = mpStorage,
        )
        authRepository.emit(AuthState.Authenticated("bob"))
        advanceUntilIdle()
        gate.complete(thread(page = 3, totalPages = 9, messages = listOf(post(123))))
        advanceUntilIdle()

        // Only bob's landing reached the MPStorage sync — alice's sealed (cancelled) job never did.
        assertEquals(1, mpStorage.ifPresentCalls.size)
        assertEquals(1, mpStorage.ifPresentCalls.single().page)
        // C2 — and the expectedPseudo passed is bob's (the session that actually read the page), so
        // the repo's identity guard compares against the right account.
        assertEquals("bob", mpStorage.ifPresentPseudos.single())
    }

    // #612 — participant roster.

    @Test
    fun `openRoster lazily fetches the reply form and exposes the owner member list`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = "alice, bob, Bébé Yoda")

        val viewModel = threadViewModel(repository, writeRepository = write)
        // LAZY: no fetch on screen entry.
        assertEquals(PrivateMessageThreadUiState.Roster.Hidden, viewModel.state.value.roster)

        viewModel.openRoster()
        advanceUntilIdle()

        val roster = viewModel.state.value.roster
        assertTrue("expected Loaded, got $roster", roster is PrivateMessageThreadUiState.Roster.Loaded)
        // The owner (« xaat », the authenticated pseudo) is PREPENDED: HFR's `newdest` lists the members
        // MINUS the creator, but the « Participants » sheet must show the full group including the viewer.
        assertEquals(
            listOf("xaat", "alice", "bob", "Bébé Yoda"),
            (roster as PrivateMessageThreadUiState.Roster.Loaded).members,
        )
        coVerify(exactly = 1) { write.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `dismissing the roster mid-load cancels the fetch so a late response cannot reopen it`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        // Never resolves within the test: the dismiss must cancel it before it can answer.
        coEvery { write.fetchReplyForm(any(), any()) } coAnswers { kotlinx.coroutines.awaitCancellation() }

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster() // fetch in flight (not advanced)
        viewModel.dismissRoster()
        advanceUntilIdle()

        assertEquals(PrivateMessageThreadUiState.Roster.Hidden, viewModel.state.value.roster)
    }

    @Test
    fun `openRoster maps a one-to-one MP (no roster) to Unavailable`() = runTest {
        // #618 — only a form with NO « Destinataires » row at all (recipientsRoster == null AND no
        // newdest) maps to Unavailable: a one-to-one MP. A non-owner DT now resolves to Loaded.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        // Neither newdest nor a read-only roster → a one-to-one MP.
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = null, roster = null)

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()

        assertEquals(PrivateMessageThreadUiState.Roster.Unavailable, viewModel.state.value.roster)
    }

    @Test
    fun `openRoster exposes the full roster of a NON-owner DT and marks it non-manageable`() = runTest {
        // #618 — a participant's message.php form carries the roster as a read-only span (no newdest).
        // The sheet must still show the FULL group (viewer prepended), but flag it as not manageable.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        // No newdest (not the owner) but a read-only roster CSV (minus the viewer « xaat »).
        coEvery {
            write.fetchReplyForm(any(), any())
        } returns replyForm(newdest = null, roster = "alice, bob, TestOwner")

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()

        val roster = viewModel.state.value.roster
        assertTrue("expected Loaded, got $roster", roster is PrivateMessageThreadUiState.Roster.Loaded)
        roster as PrivateMessageThreadUiState.Roster.Loaded
        assertEquals(listOf("xaat", "alice", "bob", "TestOwner"), roster.members)
        assertFalse("a non-owner cannot manage the recipients", roster.canManageRecipients)
    }

    @Test
    fun `openRoster marks the owner roster as manageable`() = runTest {
        // #618 — an owner form (newdest present) → the roster is editable, so canManageRecipients=true
        // and the « Gérer les destinataires » entry is offered.
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = "alice, bob")

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()

        val roster = viewModel.state.value.roster
        assertTrue("expected Loaded, got $roster", roster is PrivateMessageThreadUiState.Roster.Loaded)
        assertTrue((roster as PrivateMessageThreadUiState.Roster.Loaded).canManageRecipients)
    }

    @Test
    fun `re-opening the roster reuses the cached form without a second fetch`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = "alice, bob")

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()
        viewModel.dismissRoster()
        assertEquals(PrivateMessageThreadUiState.Roster.Hidden, viewModel.state.value.roster)

        viewModel.openRoster()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.roster is PrivateMessageThreadUiState.Roster.Loaded)
        // The cache served the second open — exactly one network fetch overall.
        coVerify(exactly = 1) { write.fetchReplyForm(any(), any()) }
    }

    @Test
    fun `a roster load failure surfaces an Error kept open for retry`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
        val write = mockk<PrivateMessageWriteRepository>()
        coEvery { write.fetchReplyForm(any(), any()) } throws IOException("offline")

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()

        val roster = viewModel.state.value.roster
        assertTrue("expected Error, got $roster", roster is PrivateMessageThreadUiState.Roster.Error)
        // #316 — no raw message, only the type-derived kind.
        assertEquals(HfrErrorKind.Network, (roster as PrivateMessageThreadUiState.Roster.Error).kind)

        // Retry succeeds → Loaded.
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = "alice")
        viewModel.retryRoster()
        advanceUntilIdle()
        assertTrue(viewModel.state.value.roster is PrivateMessageThreadUiState.Roster.Loaded)
    }

    // #618 — `newdest` = the owner's editable input (drives canManageRecipients) ; `roster` = the
    // read-only roster CSV the parser surfaces for EVERY member. An owner form sets both to the same
    // value (parser reuses newdest) ; a non-owner form sets only `roster` (read-only span, no newdest).
    private fun replyForm(newdest: String?, roster: String? = newdest) = ReplyForm(
        hashCheck = "h",
        sujet = "Sujet",
        hiddenFields = buildMap {
            put("cat", "prive")
            put("post", "42")
            if (newdest != null) put("newdest", newdest)
        },
        isAnonymous = false,
        recipientsRoster = roster,
    )

    private fun thread(
        page: Int,
        totalPages: Int,
        messages: List<Post> = emptyList(),
    ) = PrivateMessageThread(
        threadId = 42,
        subject = "Sujet",
        correspondent = "Correspondant",
        messages = messages,
        page = page,
        totalPages = totalPages,
        canReply = true,
    )

    private fun network(thread: PrivateMessageThread): Flow<PrivateMessageThreadPage> =
        flowOf(networkPage(thread))

    private fun networkPage(thread: PrivateMessageThread) = PrivateMessageThreadPage(
        thread = thread,
        source = PrivateMessageThreadPage.Source.NETWORK,
    )

    private fun failure(error: Exception): Flow<PrivateMessageThreadPage> = flow { throw error }

    private fun post(numreponse: Int, author: String = "auteur") = Post(
        numreponse = numreponse,
        author = author,
        date = Instant.EPOCH,
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

    private fun loadedRepository(): MessagesRepository = mockk<MessagesRepository>().also { repository ->
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns network(thread(page = 1, totalPages = 1))
    }

    private class FakePostImageSaver(
        private val behaviour: (String) -> SavedPostImage,
    ) : PostImageSaver {
        val requests = mutableListOf<String>()

        override suspend fun save(url: String): SavedPostImage {
            requests += url
            return behaviour(url)
        }
    }

    private class FakeAuthRepository(
        initial: AuthState = AuthState.Authenticated("xaat"),
    ) : AuthRepository {
        private val state = MutableStateFlow(initial)

        override fun observeAuthState(): Flow<AuthState> = state.asStateFlow()

        override suspend fun login(pseudo: String, password: String): Result<AuthState.Authenticated> =
            Result.failure(IllegalStateException("not used"))

        override suspend fun logout() {
            state.value = AuthState.Anonymous
        }

        suspend fun emit(authState: AuthState) {
            state.emit(authState)
        }
    }

    private class FakeAuthorRoleRepository(
        private val staff: Map<String, AuthorRole> = emptyMap(),
        private val gate: CompletableDeferred<Map<String, AuthorRole>>? = null,
        private val error: Throwable? = null,
    ) : AuthorRoleRepository {
        var calls: Int = 0
            private set

        override suspend fun getStaff(): Map<String, AuthorRole> {
            calls++
            error?.let { throw it }
            return gate?.await() ?: staff
        }

        override suspend fun getRole(profileId: Int): AuthorRole? = null
    }

    private class FakeBlacklistRepository(
        initial: Set<String> = emptySet(),
    ) : BlacklistRepository {
        private val canonicals = MutableStateFlow(initial)
        var subscriptions: Int = 0
            private set

        override fun observeEntries(): Flow<List<BlacklistEntry>> =
            error("entry management is not used by the thread VM")

        override fun observeBlockedCanonicals(): Flow<Set<String>> = canonicals
            .onStart { subscriptions += 1 }

        override suspend fun isBlocked(pseudo: String): Boolean =
            canonicalizePseudo(pseudo) in canonicals.value

        override suspend fun block(pseudo: String) {
            canonicals.value = canonicals.value + canonicalizePseudo(pseudo)
        }

        override suspend fun unblock(pseudo: String) {
            canonicals.value = canonicals.value - canonicalizePseudo(pseudo)
        }

        suspend fun emit(blocked: Set<String>) {
            canonicals.emit(blocked)
        }
    }

    /** In-memory [PrivateMessageReadPositionStore] — `saved` exposes writes for assertions. */
    private class FakeReadPositionStore(
        initial: Map<Int, Int> = emptyMap(),
    ) : PrivateMessageReadPositionStore {
        val saved = initial.toMutableMap()

        /** Owner (lowercased) the most recent save was attributed to — for #430/#462 assertions. */
        var lastSaveOwner: String? = null

        /** When set, the NEXT [savePage] parks on it before writing (cleared on consumption). */
        var blockNextSave: CompletableDeferred<Unit>? = null

        override suspend fun readPage(owner: String?, threadId: Int): Int? = saved[threadId]

        override suspend fun savePage(owner: String?, threadId: Int, page: Int) {
            blockNextSave?.let { gate ->
                blockNextSave = null
                gate.await()
            }
            lastSaveOwner = owner?.lowercase()
            saved[threadId] = page
        }
    }

    /**
     * Fake [MpStorageRepository] for the #597 auto-sync trigger. Records every
     * [writeBackFlagIfPresent] entry (so a test can assert the page/anchor/uri the trigger built),
     * returns [result] (default = the opt-in-OFF nominal case), or throws [thrown] to prove the sync
     * failure is swallowed. The read / manual-write paths are never exercised by the thread VM.
     */
    private class FakeMpStorageRepository(
        private val result: MpStorageWriteResult = MpStorageWriteResult.DisabledByPreference,
        private val thrown: Throwable? = null,
    ) : MpStorageRepository {
        val ifPresentCalls = mutableListOf<MpStorageFlagEntry>()

        /** C2 — the `expectedPseudo` the VM snapshotted and passed for each call, in order. */
        val ifPresentPseudos = mutableListOf<String>()

        override suspend fun fetchStorage() = error("read path not used by the thread VM")

        override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
            error("the auto trigger uses writeBackFlagIfPresent, never writeBackFlag")

        override suspend fun writeBackFlagIfPresent(
            entry: MpStorageFlagEntry,
            expectedPseudo: String,
        ): MpStorageWriteResult {
            ifPresentCalls += entry
            ifPresentPseudos += expectedPseudo
            thrown?.let { throw it }
            return result
        }

        override suspend fun previewWriteBackFlag(entry: MpStorageFlagEntry): MpStorageWritePreview =
            error("preview path not used by the thread VM")
    }
}
