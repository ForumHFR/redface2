package fr.forumhfr.redface2.feature.settings

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class MesImagesViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `emits the records from the repository flow scoped to the lowercased pseudo`() = runTest {
        val upload = mockk<UploadRepository>()
        val records = listOf(record("a"), record("b"))
        // The active pseudo is "XaaT" ; the screen must scope to its lowercased form.
        coEvery { upload.observeUploads("xaat") } returns flowOf(records)

        val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Authenticated("XaaT")), upload)

        val mode = viewModel.state.value.mode
        assertTrue(mode is MesImagesUiState.Mode.Content)
        assertEquals(records, (mode as MesImagesUiState.Mode.Content).images)
        coVerify(exactly = 1) { upload.observeUploads("xaat") }
    }

    @Test
    fun `shows the empty content state when the repository emits no records`() = runTest {
        val upload = mockk<UploadRepository>()
        coEvery { upload.observeUploads(any()) } returns flowOf(emptyList())

        val viewModel = MesImagesViewModel(FakeAuthRepository(), upload)

        val mode = viewModel.state.value.mode
        assertTrue(mode is MesImagesUiState.Mode.Content)
        assertTrue((mode as MesImagesUiState.Mode.Content).images.isEmpty())
    }

    @Test
    fun `shows RequiresLogin and never reads uploads when anonymous`() = runTest {
        val upload = mockk<UploadRepository>()

        val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Anonymous), upload)

        assertEquals(MesImagesUiState.Mode.RequiresLogin, viewModel.state.value.mode)
        coVerify(exactly = 0) { upload.observeUploads(any()) }
    }

    @Test
    fun `requestDelete opens the confirm dialog and cancel closes it without calling the repository`() =
        runTest {
            val upload = mockk<UploadRepository>()
            coEvery { upload.observeUploads(any()) } returns flowOf(listOf(record("a")))

            val viewModel = MesImagesViewModel(FakeAuthRepository(), upload)
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

            val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload)

            viewModel.requestDelete(target)
            viewModel.confirmDelete()
            advanceUntilIdle()

            coVerify(exactly = 1) { upload.delete(target, "xaat") }
            assertNull(viewModel.state.value.pendingDeletion)
            assertEquals(MesImagesUiState.DeletionMessage.Confirmed, viewModel.state.value.deletionMessage)
        }

    @Test
    fun `confirmDelete surfaces a best-effort message when the host did not confirm`() = runTest {
        val upload = mockk<UploadRepository>()
        val target = record("a", deleteHandle = null)
        coEvery { upload.observeUploads(any()) } returns flowOf(listOf(target))
        coEvery { upload.delete(target, "xaat") } returns false

        val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload)

        viewModel.requestDelete(target)
        viewModel.confirmDelete()
        advanceUntilIdle()

        assertEquals(MesImagesUiState.DeletionMessage.BestEffort, viewModel.state.value.deletionMessage)
    }

    @Test
    fun `consumeDeletionMessage clears the one-shot snackbar`() = runTest {
        val upload = mockk<UploadRepository>()
        val target = record("a")
        coEvery { upload.observeUploads(any()) } returns flowOf(listOf(target))
        coEvery { upload.delete(any(), any()) } returns true

        val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload)
        viewModel.requestDelete(target)
        viewModel.confirmDelete()
        advanceUntilIdle()
        assertEquals(MesImagesUiState.DeletionMessage.Confirmed, viewModel.state.value.deletionMessage)

        viewModel.consumeDeletionMessage()
        assertNull(viewModel.state.value.deletionMessage)
    }

    @Test
    fun `the list reactively updates when the repository flow re-emits`() = runTest {
        val upload = mockk<UploadRepository>()
        val uploads = MutableStateFlow(listOf(record("a"), record("b")))
        coEvery { upload.observeUploads("xaat") } returns uploads

        val viewModel = MesImagesViewModel(FakeAuthRepository(AuthState.Authenticated("xaat")), upload)

        viewModel.state.test {
            assertEquals(2, (awaitItem().mode as MesImagesUiState.Mode.Content).images.size)
            uploads.value = listOf(record("a"))
            advanceUntilIdle()
            assertEquals(1, (awaitItem().mode as MesImagesUiState.Mode.Content).images.size)
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

            val viewModel = MesImagesViewModel(auth, upload)
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
