package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.upload.UploadProviderId
import fr.forumhfr.redface2.core.domain.upload.UploadRepository
import fr.forumhfr.redface2.core.domain.upload.UploadedImageRecord
import fr.forumhfr.redface2.core.model.AuthState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class MyImagesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * #1144 — stands in for the `@ApplicationScope` singleton the ViewModel now injects. Its
     * `SupervisorJob` is never cancelled by the tests, exactly like the process-lifetime scope.
     */
    private fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    /**
     * #1144 — destroys [target] the way the framework does on screen leave: `ViewModelStore.clear()`
     * cancels `viewModelScope` and calls `onCleared()`.
     */
    private fun destroyViewModel(target: ViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = target as T
            },
        ).get(target::class.java)
        store.clear()
    }

    @Test
    fun `emits the records from the repository flow scoped to the lowercased pseudo`() = runTest {
        val upload = mockk<UploadRepository>()
        val records = listOf(record("a"), record("b"))
        // The active pseudo is "XaaT" ; the screen must scope to its lowercased form.
        coEvery { upload.observeUploads("xaat") } returns flowOf(records)

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("XaaT")), upload, appScope())

        val mode = viewModel.state.value.mode
        assertTrue(mode is MyImagesUiState.Mode.Content)
        assertEquals(records, (mode as MyImagesUiState.Mode.Content).images)
        coVerify(exactly = 1) { upload.observeUploads("xaat") }
    }

    @Test
    fun `shows the empty content state when the repository emits no records`() = runTest {
        val upload = mockk<UploadRepository>()
        coEvery { upload.observeUploads(any()) } returns flowOf(emptyList())

        val viewModel = MyImagesViewModel(FakeAuthRepository(), upload, appScope())

        val mode = viewModel.state.value.mode
        assertTrue(mode is MyImagesUiState.Mode.Content)
        assertTrue((mode as MyImagesUiState.Mode.Content).images.isEmpty())
    }

    @Test
    fun `shows RequiresLogin and never reads uploads when anonymous`() = runTest {
        val upload = mockk<UploadRepository>()

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Anonymous), upload, appScope())

        assertEquals(MyImagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) { upload.observeUploads(any()) }
    }

    @Test
    fun `requestDelete opens the confirm dialog and cancel closes it without calling the repository`() =
        runTest {
            val upload = mockk<UploadRepository>()
            coEvery { upload.observeUploads(any()) } returns flowOf(listOf(record("a")))

            val viewModel = MyImagesViewModel(FakeAuthRepository(), upload, appScope())
            val target = record("a")

            viewModel.requestDelete(target)
            assertEquals(target, viewModel.state.value.pendingDeletion)

            viewModel.cancelDelete()
            assertNull(viewModel.state.value.pendingDeletion)
            coVerify(exactly = 0) { upload.delete(any(), any()) }
        }

    @Test
    fun `confirmDelete calls the repository with the right record and userId and reflects a confirmed host outcome`() =
        runTest {
            val upload = mockk<UploadRepository>()
            val target = record("a")
            coEvery { upload.observeUploads("xaat") } returns flowOf(listOf(target))
            coEvery { upload.delete(target, "xaat") } returns true

            val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload, appScope())

            viewModel.requestDelete(target)
            viewModel.confirmDelete()
            advanceUntilIdle()

            coVerify(exactly = 1) { upload.delete(target, "xaat") }
            assertNull(viewModel.state.value.pendingDeletion)
            assertEquals(MyImagesUiState.DeletionMessage.Confirmed, viewModel.state.value.deletionMessage)
        }

    @Test
    fun `confirmDelete surfaces a best-effort message when the host did not confirm`() = runTest {
        val upload = mockk<UploadRepository>()
        val target = record("a", deleteHandle = null)
        coEvery { upload.observeUploads(any()) } returns flowOf(listOf(target))
        coEvery { upload.delete(target, "xaat") } returns false

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload, appScope())

        viewModel.requestDelete(target)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(MyImagesUiState.DeletionMessage.BestEffort, viewModel.state.value.deletionMessage)
    }

    @Test
    fun `a confirmed image deletion outlives the ViewModel destroyed mid-request (#1144)`() = runTest {
        // Same class of defect as the four topic/flags mutations: a user-confirmed destructive host
        // call left on `viewModelScope`. Here the local row is evicted by the repository AFTER the
        // host round-trip, so an aborted delete would strand the picture on Imgur/Diberie.
        val scope = appScope()
        val gate = CompletableDeferred<Boolean>()
        val completed = CompletableDeferred<Unit>()
        val upload = mockk<UploadRepository>()
        val target = record("a")
        coEvery { upload.observeUploads(any()) } returns flowOf(listOf(target))
        coEvery { upload.delete(target, "xaat") } coAnswers {
            // Suspends until the test releases it — and only records completion PAST the suspension,
            // so a cancelled call can never satisfy the assertion below.
            val confirmed = gate.await()
            completed.complete(Unit)
            confirmed
        }

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload, scope)
        viewModel.requestDelete(target)
        viewModel.confirmDelete()
        advanceUntilIdle()
        assertTrue("the host DELETE is in flight", !completed.isCompleted)

        destroyViewModel(viewModel)
        advanceUntilIdle()

        gate.complete(true)
        advanceUntilIdle()

        assertTrue(
            "the deletion the user confirmed must still reach the host after the screen is gone",
            completed.isCompleted,
        )
        coVerify(exactly = 1) { upload.delete(target, "xaat") }
    }

    @Test
    fun `consumeDeletionMessage clears the one-shot snackbar`() = runTest {
        val upload = mockk<UploadRepository>()
        val target = record("a")
        coEvery { upload.observeUploads(any()) } returns flowOf(listOf(target))
        coEvery { upload.delete(any(), any()) } returns true

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload, appScope())
        viewModel.requestDelete(target)
        viewModel.confirmDelete()
        advanceUntilIdle()
        assertEquals(MyImagesUiState.DeletionMessage.Confirmed, viewModel.state.value.deletionMessage)

        viewModel.consumeDeletionMessage()
        assertNull(viewModel.state.value.deletionMessage)
    }

    @Test
    fun `the list reactively updates when the repository flow re-emits`() = runTest {
        val upload = mockk<UploadRepository>()
        val uploads = MutableStateFlow(listOf(record("a"), record("b")))
        coEvery { upload.observeUploads("xaat") } returns uploads

        val viewModel = MyImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload, appScope())

        viewModel.state.test {
            assertEquals(2, (awaitItem().mode as MyImagesUiState.Mode.Content).images.size)
            uploads.value = listOf(record("a"))
            advanceUntilIdle()
            assertEquals(1, (awaitItem().mode as MyImagesUiState.Mode.Content).images.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a session switch dismisses an open delete dialog (it belonged to the previous account)`() =
        runTest {
            val upload = mockk<UploadRepository>()
            val target = record("a")
            coEvery { upload.observeUploads("alice") } returns flowOf(listOf(target))
            coEvery { upload.observeUploads("bob") } returns flowOf(emptyList())
            val auth = FakeAuthRepository(AuthState.Authenticated("alice"))

            val viewModel = MyImagesViewModel(auth, upload, appScope())
            viewModel.requestDelete(target)
            assertEquals(target, viewModel.state.value.pendingDeletion)

            auth.switchTo(AuthState.Authenticated("bob"))
            advanceUntilIdle()

            assertNull(viewModel.state.value.pendingDeletion)
        }

    private fun record(picId: String, deleteHandle: String? = "del-$picId") = UploadedImageRecord(
        provider = UploadProviderId.IMGUR,
        picId = picId,
        imageUrl = "https://i.imgur.com/$picId.png",
        thumbnailUrl = "https://i.imgur.com/${picId}t.png",
        deleteHandle = deleteHandle,
        uploadedAt = Instant.EPOCH,
        expiresAt = null,
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

        fun switchTo(authState: AuthState) {
            state.value = authState
        }
    }
}
