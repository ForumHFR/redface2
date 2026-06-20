package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageDocument
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MpStorageInspectorViewModelTest {

    @Before
    fun setUp() {
        // Unconfined → the init refresh() runs eagerly to completion, so state.value is terminal.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `anonymous yields NotAuthenticated and never fetches`() = runTest {
        val repo = FakeMpStorageRepository(MpStorageResult.NotFound)

        val viewModel = MpStorageInspectorViewModel(repo, FakeLocationStore(), FakeAuthRepository(AuthState.Anonymous))

        assertEquals(MpStorageInspectorUiState.NotAuthenticated, viewModel.state.value)
        assertEquals("anonymous must not hit the network", 0, repo.fetchCount)
    }

    @Test
    fun `found document maps to Loaded with source, entries, envelope and cached location`() = runTest {
        val entries = listOf(
            MpStorageFlagEntry(threadId = 9100200, page = 12, numreponse = 9100201, uri = "t9100201"),
        )
        val document = MpStorageDocument(sourceName = "DTCloud", mpFlags = entries, rawEnvelope = "{\"data\":[]}")
        val location = MpStorageLocation(threadId = 9100200, numreponse = 9100201)

        val viewModel = MpStorageInspectorViewModel(
            FakeMpStorageRepository(MpStorageResult.Found(document)),
            FakeLocationStore(location),
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )

        val state = viewModel.state.value
        assertTrue(state is MpStorageInspectorUiState.Loaded)
        state as MpStorageInspectorUiState.Loaded
        assertEquals("DTCloud", state.sourceName)
        assertEquals(entries, state.entries)
        assertEquals("{\"data\":[]}", state.rawEnvelope)
        assertEquals(location, state.location)
    }

    @Test
    fun `location store is queried with the lowercased pseudo`() = runTest {
        val store = FakeLocationStore(MpStorageLocation(1, 2))

        MpStorageInspectorViewModel(
            FakeMpStorageRepository(MpStorageResult.Found(MpStorageDocument(null, emptyList(), "{}"))),
            store,
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )

        assertEquals("xatelitte", store.lastOwner)
    }

    @Test
    fun `not found maps to NotFound`() = runTest {
        val viewModel = MpStorageInspectorViewModel(
            FakeMpStorageRepository(MpStorageResult.NotFound),
            FakeLocationStore(),
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )

        assertEquals(MpStorageInspectorUiState.NotFound, viewModel.state.value)
    }

    @Test
    fun `unreadable maps to Unreadable`() = runTest {
        val viewModel = MpStorageInspectorViewModel(
            FakeMpStorageRepository(MpStorageResult.Unreadable),
            FakeLocationStore(),
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )

        assertEquals(MpStorageInspectorUiState.Unreadable, viewModel.state.value)
    }

    @Test
    fun `fetch failure maps to Error carrying the message`() = runTest {
        val viewModel = MpStorageInspectorViewModel(
            FakeMpStorageRepository(error = IllegalStateException("boom")),
            FakeLocationStore(),
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )

        val state = viewModel.state.value
        assertTrue(state is MpStorageInspectorUiState.Error)
        assertEquals("boom", (state as MpStorageInspectorUiState.Error).message)
    }

    @Test
    fun `logout while loaded clears the previous account's envelope to NotAuthenticated`() = runTest {
        // Codex review — a mid-life logout must not leave the previous account's raw envelope on screen.
        val auth = FakeAuthRepository(AuthState.Authenticated("XaTelitte"))
        val document = MpStorageDocument(sourceName = "DTCloud", mpFlags = emptyList(), rawEnvelope = "{\"secret\":1}")
        val viewModel = MpStorageInspectorViewModel(
            FakeMpStorageRepository(MpStorageResult.Found(document)),
            FakeLocationStore(MpStorageLocation(1, 2)),
            auth,
        )
        assertTrue(viewModel.state.value is MpStorageInspectorUiState.Loaded)

        auth.setState(AuthState.Anonymous)

        assertEquals(MpStorageInspectorUiState.NotAuthenticated, viewModel.state.value)
    }

    @Test
    fun `account switch reloads scoped to the new owner`() = runTest {
        val auth = FakeAuthRepository(AuthState.Authenticated("Alice"))
        val store = FakeLocationStore(MpStorageLocation(1, 2))
        val repo = FakeMpStorageRepository(MpStorageResult.Found(MpStorageDocument(null, emptyList(), "{}")))
        MpStorageInspectorViewModel(repo, store, auth)
        assertEquals("alice", store.lastOwner)
        assertEquals(1, repo.fetchCount)

        auth.setState(AuthState.Authenticated("Bob"))

        assertEquals("bob", store.lastOwner)
        assertEquals(2, repo.fetchCount)
    }

    @Test
    fun `a switch mid-fetch never publishes the previous owner's data`() = runTest {
        // Codex review (success-race): the first owner's in-flight fetch must not clobber the new
        // owner's state when it completes late.
        val gate = CompletableDeferred<Unit>()
        val repo = object : MpStorageRepository {
            var calls = 0
            override suspend fun fetchStorage(): MpStorageResult {
                calls += 1
                val first = calls == 1
                if (first) gate.await() // park the first (Alice) fetch until released
                return MpStorageResult.Found(
                    MpStorageDocument(
                        sourceName = if (first) "ALICE" else "BOB",
                        mpFlags = emptyList(),
                        rawEnvelope = if (first) "{\"alice\":1}" else "{\"bob\":1}",
                    ),
                )
            }

            // Read-only inspector: a write must NEVER happen here — fail loudly if one slips in (Codex review).
            override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
                error("Unexpected writeBackFlag call in the read-only inspector test")

            override suspend fun writeBackFlagIfPresent(entry: MpStorageFlagEntry): MpStorageWriteResult =
                error("Unexpected writeBackFlagIfPresent call in the read-only inspector test")

            override suspend fun previewWriteBackFlag(
                entry: MpStorageFlagEntry,
            ): fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview =
                error("Unexpected previewWriteBackFlag call in the read-only inspector test")
        }
        val auth = FakeAuthRepository(AuthState.Authenticated("Alice"))
        val viewModel = MpStorageInspectorViewModel(repo, FakeLocationStore(), auth)
        assertEquals(MpStorageInspectorUiState.Loading, viewModel.state.value)

        auth.setState(AuthState.Authenticated("Bob"))
        val afterSwitch = viewModel.state.value
        assertTrue(afterSwitch is MpStorageInspectorUiState.Loaded)
        assertEquals("BOB", (afterSwitch as MpStorageInspectorUiState.Loaded).sourceName)

        // Release Alice's late fetch — must NOT overwrite Bob's state.
        gate.complete(Unit)
        advanceUntilIdle()
        val finalState = viewModel.state.value
        assertTrue(finalState is MpStorageInspectorUiState.Loaded)
        assertEquals("BOB", (finalState as MpStorageInspectorUiState.Loaded).sourceName)
    }

    @Test
    fun `refresh re-fetches the storage`() = runTest {
        val repo = FakeMpStorageRepository(MpStorageResult.NotFound)
        val viewModel = MpStorageInspectorViewModel(
            repo,
            FakeLocationStore(),
            FakeAuthRepository(AuthState.Authenticated("XaTelitte")),
        )
        assertEquals(1, repo.fetchCount)

        viewModel.refresh()

        assertEquals(2, repo.fetchCount)
    }

    private class FakeMpStorageRepository(
        private val result: MpStorageResult = MpStorageResult.NotFound,
        private val error: Throwable? = null,
    ) : MpStorageRepository {
        var fetchCount = 0
            private set

        override suspend fun fetchStorage(): MpStorageResult {
            fetchCount++
            error?.let { throw it }
            return result
        }

        // Read-only inspector: a write must NEVER happen here — fail loudly if one slips in (Codex review).
        override suspend fun writeBackFlag(entry: MpStorageFlagEntry): MpStorageWriteResult =
            error("Unexpected writeBackFlag call in the read-only inspector test")

        override suspend fun writeBackFlagIfPresent(entry: MpStorageFlagEntry): MpStorageWriteResult =
            error("Unexpected writeBackFlagIfPresent call in the read-only inspector test")

        override suspend fun previewWriteBackFlag(
            entry: MpStorageFlagEntry,
        ): fr.forumhfr.redface2.core.domain.mpstorage.MpStorageWritePreview =
            error("Unexpected previewWriteBackFlag call in the read-only inspector test")
    }

    private class FakeLocationStore(
        private val location: MpStorageLocation? = null,
    ) : MpStorageLocationStore {
        var lastOwner: String? = null
            private set

        override suspend fun read(owner: String?): MpStorageLocation? {
            lastOwner = owner
            return location
        }

        override suspend fun save(owner: String?, threadId: Int, numreponse: Int) = Unit

        override suspend fun clear(owner: String?) = Unit
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

        /** Drive an auth transition (logout / account switch) so the ViewModel's collector reacts. */
        fun setState(newState: AuthState) {
            state.value = newState
        }
    }
}
