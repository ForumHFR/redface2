package fr.forumhfr.redface2.feature.messages

import fr.forumhfr.redface2.core.domain.messages.MessagesRepository
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        correspondent = "Correspondant",
        subject = "Sujet",
        page = 1,
    )

    @Test
    fun `loads the thread on init and forwards the inbox correspondent as fallback`() = runTest {
        val repository = mockk<MessagesRepository>()
        val thread = thread(page = 1, totalPages = 1)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } returns thread

        val viewModel = PrivateMessageThreadViewModel(request, repository)

        val state = viewModel.state.value
        assertTrue(state.mode is PrivateMessageThreadUiState.Mode.Content)
        assertEquals(thread, (state.mode as PrivateMessageThreadUiState.Mode.Content).thread)
        // The inbox-row correspondent must reach the repository (parser fallback contract).
        coVerify(exactly = 1) {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        }
    }

    @Test
    fun `surfaces a load failure as Error`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } throws IOException("offline")

        val viewModel = PrivateMessageThreadViewModel(request, repository)

        val mode = viewModel.state.value.mode
        assertTrue(mode is PrivateMessageThreadUiState.Mode.Error)
        assertEquals("offline", (mode as PrivateMessageThreadUiState.Mode.Error).message)
    }

    @Test
    fun `selectPage loads the requested page`() = runTest {
        val repository = mockk<MessagesRepository>()
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 1, fallbackCorrespondent = "Correspondant")
        } returns thread(page = 1, totalPages = 2)
        coEvery {
            repository.getPrivateMessageThread(threadId = 42, page = 2, fallbackCorrespondent = "Correspondant")
        } returns thread(page = 2, totalPages = 2)

        val viewModel = PrivateMessageThreadViewModel(request, repository)
        viewModel.selectPage(2)

        val state = viewModel.state.value
        assertEquals(2, state.page)
        assertTrue(state.canGoPrevious)
    }

    private fun thread(page: Int, totalPages: Int) = PrivateMessageThread(
        threadId = 42,
        subject = "Sujet",
        correspondent = "Correspondant",
        messages = emptyList(),
        page = page,
        totalPages = totalPages,
        canReply = true,
    )
}
