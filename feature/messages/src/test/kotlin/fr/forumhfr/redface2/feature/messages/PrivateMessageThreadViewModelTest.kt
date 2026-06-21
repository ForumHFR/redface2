package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageReadPositionStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun threadViewModel(
        repository: MessagesRepository,
        authRepository: AuthRepository = FakeAuthRepository(),
        readPositionStore: PrivateMessageReadPositionStore = FakeReadPositionStore(),
        mpStorageRepository: MpStorageRepository = FakeMpStorageRepository(),
        writeRepository: PrivateMessageWriteRepository = mockk(relaxed = true),
    ) = PrivateMessageThreadViewModel(
        request, repository, authRepository, readPositionStore, mpStorageRepository, writeRepository,
    )

    @Test
    fun `loads the thread on init without private route metadata fallback`() = runTest {
        val repository = mockk<MessagesRepository>()
        val thread = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns thread

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
    fun `anonymous state does not fetch the private thread`() = runTest {
        val repository = mockk<MessagesRepository>()

        val viewModel = PrivateMessageThreadViewModel(
            request = request,
            repository = repository,
            authRepository = FakeAuthRepository(AuthState.Anonymous),
            readPositionStore = FakeReadPositionStore(),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
        )

        assertEquals(PrivateMessageThreadUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) {
            repository.getPrivateMessageThread(any(), any(), any())
        }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } throws IOException("offline")

        val viewModel = threadViewModel(repository)

        // #316: the Error mode carries NO raw throwable message (privacy — it can embed the private
        // conversation URL). The only detail is the #324 type-derived kind (safe closed enum).
        val mode = viewModel.state.value.mode
        assertTrue(mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals(HfrErrorKind.Network, (mode as PrivateMessageThreadUiState.Mode.Error).kind)
    }

    @Test
    fun `surfaces an HFR 5xx load failure with the ServerDown kind`() = runTest {
        // #324 — an HFR outage must be distinguishable from a network cut on a conversation,
        // still without any raw message (the kind is derived from the exception TYPE only —
        // never from a string that could embed forum2.php?cat=prive&post=<id>).
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } throws HfrServerException(code = 500, url = "https://forum.hardware.fr/forum2.php")

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
        } returns thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } returns thread(page = 2, totalPages = 2)

        val viewModel = threadViewModel(repository)
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertTrue(state.canGoPrevious)
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
        } returns pageOne
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = null)
        } coAnswers { gate.await() }

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
    fun `refresh re-fetches the displayed page in place`() = runTest {
        val repository = mockk<MessagesRepository>()
        val first = thread(page = 1, totalPages = 1)
        val updated = thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns first andThen updated

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
        } returns pageOne andThenThrows IOException("offline")

        val viewModel = threadViewModel(repository)
        viewModel.refresh()

        val state = viewModel.state.value
        assertFalse(state.isRefreshing)
        assertEquals(pageOne, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        assertEquals(PrivateMessageThreadEffect.RefreshFailed, viewModel.effects.first())
    }

    @Test
    fun `refresh is a no-op without loaded content`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } throws IOException("offline")

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
        } returns thread(page = 1, totalPages = 1)

        val viewModel = threadViewModel(repository, authRepository)
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
        } returns thread(page = 7, totalPages = 9)

        PrivateMessageThreadViewModel(
            request = request,
            repository = repository,
            authRepository = FakeAuthRepository(),
            readPositionStore = FakeReadPositionStore(initial = mapOf(42 to 7)),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
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
        } returns thread(page = 9, totalPages = 9)

        PrivateMessageThreadViewModel(
            request = PrivateMessageThreadRequest(threadId = 42, page = 9),
            repository = repository,
            authRepository = FakeAuthRepository(),
            readPositionStore = FakeReadPositionStore(initial = mapOf(42 to 3)),
            mpStorageRepository = FakeMpStorageRepository(),
            writeRepository = mockk(relaxed = true),
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
        } returns thread(page = 1, totalPages = 9)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 5, fallbackCorrespondent = null)
        } returns thread(page = 5, totalPages = 9)
        val store = FakeReadPositionStore()

        val viewModel =
            PrivateMessageThreadViewModel(
                request, repository, FakeAuthRepository(), store, FakeMpStorageRepository(),
                mockk(relaxed = true),
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
        } coAnswers { if (calls++ == 0) gate.await() else thread(page = 1, totalPages = 1) }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val store = FakeReadPositionStore()

        threadViewModel(repository, authRepository, store)
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
        } returns thread(page = 1, totalPages = 9)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 5, fallbackCorrespondent = null)
        } returns thread(page = 5, totalPages = 9)
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
        } returns thread(page = 1, totalPages = 3, messages = listOf(post(1000), post(2784595)))
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
        } returns thread(page = 1, totalPages = 1, messages = emptyList())
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
        } returns thread(page = 1, totalPages = 1, messages = listOf(post(7)))
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
        } coAnswers { if (calls++ == 0) gate.await() else thread(page = 1, totalPages = 1, messages = listOf(post(9))) }
        val authRepository = FakeAuthRepository(AuthState.Authenticated("alice"))
        val mpStorage = FakeMpStorageRepository()

        threadViewModel(repository, authRepository, mpStorageRepository = mpStorage)
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
        } returns thread(page = 1, totalPages = 1)
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
        } returns thread(page = 1, totalPages = 1)
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
    fun `openRoster maps a non-owner form to Unavailable`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns thread(page = 1, totalPages = 1)
        val write = mockk<PrivateMessageWriteRepository>()
        // No newdest → a simple participant, not the owner.
        coEvery { write.fetchReplyForm(any(), any()) } returns replyForm(newdest = null)

        val viewModel = threadViewModel(repository, writeRepository = write)
        viewModel.openRoster()
        advanceUntilIdle()

        assertEquals(PrivateMessageThreadUiState.Roster.Unavailable, viewModel.state.value.roster)
    }

    @Test
    fun `re-opening the roster reuses the cached form without a second fetch`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = null)
        } returns thread(page = 1, totalPages = 1)
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
        } returns thread(page = 1, totalPages = 1)
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

    private fun replyForm(newdest: String?) = ReplyForm(
        hashCheck = "h",
        sujet = "Sujet",
        hiddenFields = buildMap {
            put("cat", "prive")
            put("post", "42")
            if (newdest != null) put("newdest", newdest)
        },
        isAnonymous = false,
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

    private fun post(numreponse: Int) = Post(
        numreponse = numreponse,
        author = "auteur",
        date = Instant.EPOCH,
        content = PostContent(blocks = emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
    )

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
