package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProfileConfigurationRepositoryTest {
    private val client = mockk<HfrClient>()
    private val parser = mockk<HfrParser>()
    private val repository = DefaultProfileRepository(client, parser, UnconfinedTestDispatcher())

    @Test
    fun `configuration success forwards the pseudo and returns the parsed lines`() = runTest {
        val lines = listOf("AMD Ryzen 5 5600X + Noctua NH-U12S", "SSD NVMe WD Black 500Go")
        coEvery { client.getUserConfiguration("Colonel MythO") } returns "configuration response"
        every { parser.parseUserConfiguration("configuration response") } returns lines

        assertEquals(lines, repository.getUserConfiguration("Colonel MythO").getOrThrow())

        coVerify(exactly = 1) { client.getUserConfiguration("Colonel MythO") }
        coVerify(exactly = 0) { client.getProfile(any()) }
    }

    @Test
    fun `empty configuration is a successful empty list`() = runTest {
        coEvery { client.getUserConfiguration("ezzz") } returns "empty response"
        every { parser.parseUserConfiguration("empty response") } returns emptyList()

        assertEquals(emptyList<String>(), repository.getUserConfiguration("ezzz").getOrThrow())
    }

    @Test
    fun `configuration network error preserves the failure and skips parsing`() = runTest {
        val error = IOException("offline")
        coEvery { client.getUserConfiguration("XaTriX") } throws error

        assertSame(error, repository.getUserConfiguration("XaTriX").exceptionOrNull())

        verify(exactly = 0) { parser.parseUserConfiguration(any()) }
    }

    @Test
    fun `unexpected HTML is a failure rather than an empty configuration`() = runTest {
        val error = IllegalArgumentException("Missing hardware configuration heading")
        coEvery { client.getUserConfiguration("XaTriX") } returns "unexpected response"
        every { parser.parseUserConfiguration("unexpected response") } throws error

        assertSame(error, repository.getUserConfiguration("XaTriX").exceptionOrNull())
    }

    @Test
    fun `configuration cancellation propagates instead of returning failure`() = runTest {
        coEvery { client.getUserConfiguration("XaTriX") } throws CancellationException("cancelled")

        val error = runCatching { repository.getUserConfiguration("XaTriX") }.exceptionOrNull()

        assertTrue(error is CancellationException)
    }
}
