package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.model.profile.SanctionsHistory
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.profile.SanctionsHistoryParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSanctionsRepositoryTest {
    private val client = mockk<HfrClient>()
    private val parser = mockk<SanctionsHistoryParser>()
    private val repository = DefaultSanctionsRepository(client, parser, Dispatchers.Unconfined)

    @Test
    fun `fetch and parse both run on the supplied IO dispatcher`() = runBlocking {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sanctions-io") }
            .asCoroutineDispatcher().use { dispatcher ->
                val onIo = DefaultSanctionsRepository(client, parser, dispatcher)
                val history = SanctionsHistory.Loaded("XaTriX", emptyList())
                coEvery { client.fetchSanctionsHistoryPage() } coAnswers {
                    assertTrue(Thread.currentThread().name.startsWith("sanctions-io"))
                    "response"
                }
                every { parser.parse("response") } answers {
                    assertTrue(Thread.currentThread().name.startsWith("sanctions-io"))
                    history
                }
                assertEquals(history, onIo.loadSanctions().getOrThrow())
            }
    }

    @Test
    fun `missing session from the parser is preserved as a successful classification`() = runTest {
        coEvery { client.fetchSanctionsHistoryPage() } returns ""
        every { parser.parse("") } returns SanctionsHistory.SignInRequired
        assertEquals(SanctionsHistory.SignInRequired, repository.loadSanctions().getOrThrow())
    }

    @Test
    fun `network failure retains its cause without retrying`() = runTest {
        val error = IOException("offline")
        coEvery { client.fetchSanctionsHistoryPage() } throws error
        assertSame(error, repository.loadSanctions().exceptionOrNull())
        coVerify(exactly = 1) { client.fetchSanctionsHistoryPage() }
    }

    @Test
    fun `parser failure retains its cause`() = runTest {
        val error = IllegalStateException("parse failure")
        coEvery { client.fetchSanctionsHistoryPage() } returns "response"
        every { parser.parse("response") } throws error
        assertSame(error, repository.loadSanctions().exceptionOrNull())
    }

    @Test
    fun `cancellation is rethrown instead of converted to a Result failure`() = runTest {
        val cancellation = CancellationException("screen closed")
        coEvery { client.fetchSanctionsHistoryPage() } throws cancellation
        assertSame(cancellation, runCatching { repository.loadSanctions() }.exceptionOrNull())
    }
}
