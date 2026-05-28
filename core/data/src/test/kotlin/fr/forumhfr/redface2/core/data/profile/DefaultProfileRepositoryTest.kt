package fr.forumhfr.redface2.core.data.profile

import fr.forumhfr.redface2.core.model.UserProfile
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 finish (#208) — unit tests for [DefaultProfileRepository].
 *
 * The repository orchestrates network + parse and wraps both in a [Result].
 * Tests verify:
 * - successful fetch returns a [UserProfile];
 * - network failure returns a [Result.Failure];
 * - [HfrClient.getProfile] is called with the correct userId;
 * - [withContext(ioDispatcher)] is satisfied (UnconfinedTestDispatcher).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultProfileRepositoryTest {

    private val client = mockk<HfrClient>()
    private val parser = mockk<HfrParser>()
    private val dispatcher = UnconfinedTestDispatcher()

    private val repository = DefaultProfileRepository(
        client = client,
        parser = parser,
        ioDispatcher = dispatcher,
    )

    private val dummyProfile = UserProfile(
        userId = 54596,
        pseudo = "XaTriX",
        avatarUrl = null,
        registeredAt = "12/06/2002",
        postCount = 213400,
        location = "Katowice (PL)",
        signatureText = null,
    )

    @Test
    fun `getProfile returns success when network and parser succeed`() = runTest {
        val html = "<html>...</html>"
        coEvery { client.getProfile(54596) } returns html
        coEvery { parser.parseUserProfile(html, 54596) } returns dummyProfile

        val result = repository.getProfile(54596)

        assertTrue("Result should be success", result.isSuccess)
        assertEquals(dummyProfile, result.getOrNull())
    }

    @Test
    fun `getProfile calls HfrClient with the correct userId`() = runTest {
        val html = "<html>...</html>"
        coEvery { client.getProfile(15867) } returns html
        coEvery { parser.parseUserProfile(html, 15867) } returns dummyProfile.copy(userId = 15867)

        repository.getProfile(15867)

        coVerify(exactly = 1) { client.getProfile(15867) }
    }

    @Test
    fun `getProfile returns failure on network IOException`() = runTest {
        coEvery { client.getProfile(54596) } throws IOException("Network error")

        val result = repository.getProfile(54596)

        assertTrue("Result should be failure", result.isFailure)
        assertTrue("Cause should be IOException", result.exceptionOrNull() is IOException)
    }

    @Test
    fun `getProfile returns failure when parser throws`() = runTest {
        val html = "<html>bad</html>"
        coEvery { client.getProfile(54596) } returns html
        coEvery { parser.parseUserProfile(html, 54596) } throws RuntimeException("parse error")

        val result = repository.getProfile(54596)

        assertTrue("Result should be failure on parser exception", result.isFailure)
    }

    @Test
    fun `getProfile re-throws CancellationException without wrapping in Result_failure`() = runTest {
        // Review feedback I5: the legacy implementation used `runCatching`, which
        // swallows CancellationException and turns it into a `Result.failure(...)`.
        // That breaks structured concurrency — the parent job's cancellation never
        // propagates and orphaned coroutines keep running after the caller is gone.
        // The fix is a manual try/catch that rethrows CancellationException ; this
        // test guards against a regression by asserting the exception propagates and
        // is NOT swallowed into a Result.failure.
        val cancellation = CancellationException("cooperative cancel")
        coEvery { client.getProfile(54596) } throws cancellation

        var caught: Throwable? = null
        try {
            repository.getProfile(54596)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            caught = t
        }
        assertTrue(
            "CancellationException must be re-thrown by the repository — got $caught",
            caught is CancellationException,
        )
    }
}
