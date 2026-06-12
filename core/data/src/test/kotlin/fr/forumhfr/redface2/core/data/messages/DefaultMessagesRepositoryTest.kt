package fr.forumhfr.redface2.core.data.messages

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.messages.PrivateMessageListPage
import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageListParser
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageThreadParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DefaultMessagesRepositoryTest {

    @Test
    fun `observeUnreadMpCount emits null when auth state is Anonymous`() = runTest {
        val (repo, authStates) = buildRepository()

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Anonymous)
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUnreadMpCount emits parsed count when authenticated`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 7

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(7, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUnreadMpCount emits null when fetch throws (network error)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } throws IOException("offline")

        val (repo, authStates) = buildRepository(hfrClient = hfrClient)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertNull(
                "A failed fetch must surface as null rather than a stale or zero count",
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUnreadMpCount emits null when fetch throws SessionExpiredException`() = runTest {
        // The footer line is intentionally silenced on session expiry — the body of FlagsRoute
        // already surfaces a reconnect CTA via FlagsResult.Failure(SessionExpiredException),
        // so doubling that signal in the footer would be noisy. This test pins that contract:
        // the MP repository must NOT propagate SessionExpiredException to its caller, it must
        // swallow it silently like any other fetch failure.
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } throws
            SessionExpiredException("https://forum.hardware.fr/login.php")

        val (repo, authStates) = buildRepository(hfrClient = hfrClient)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertNull(
                "SessionExpiredException must surface as null in the footer (FlagsRoute body owns the CTA)",
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUnreadMpCount flips back to null when user logs out`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 3

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(3, awaitItem())

            authStates.emit(AuthState.Anonymous)
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUnreadMpCount refetches when auth state flips to Authenticated again`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returnsMany listOf(FAKE_HTML, FAKE_HTML_2)
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 1
        coEvery { parser.countUnread(FAKE_HTML_2) } returns 5

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(1, awaitItem())

            authStates.emit(AuthState.Anonymous)
            assertNull(awaitItem())

            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(5, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a page-1 inbox fetch refreshes the observed unread count for free (piggyback #313)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 2
        coEvery { parser.parseList(FAKE_HTML) } returns PrivateMessageListPage(
            page = 1,
            totalPages = 1,
            items = listOf(summary(threadId = 1, hasUnread = true), summary(threadId = 2, hasUnread = false)),
        )

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(2, awaitItem())

            repo.getPrivateMessageList(page = 1)

            assertEquals(
                "the page-1 dots must refresh the badge without a second fetch",
                1,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a deeper inbox page does NOT touch the observed count (partial view, #313)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = any()) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 2
        coEvery { parser.parseList(FAKE_HTML) } returns PrivateMessageListPage(
            page = 2,
            totalPages = 2,
            items = listOf(summary(threadId = 9, hasUnread = true)),
        )

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(2, awaitItem())

            repo.getPrivateMessageList(page = 2)

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a page-1 fetch in flight across an account switch cannot feed the new badge (#439)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returnsMany listOf(2, 3)
        lateinit var authStates: MutableSharedFlow<AuthState>
        coEvery { parser.parseList(FAKE_HTML) } coAnswers {
            // The account switch lands while the page-1 fetch is in flight : the session pseudo
            // was snapshotted at call-time (A), but by the time the response is parsed the
            // collector is already running under B. parseList is only on the
            // getPrivateMessageList path, so the badge's own countUnread fetches run free.
            authStates.emit(AuthState.Authenticated("B"))
            PrivateMessageListPage(
                page = 1,
                totalPages = 1,
                items = listOf(summary(threadId = 1, hasUnread = true)),
            )
        }

        val (repo, states) = buildRepository(hfrClient = hfrClient, parser = parser)
        authStates = states

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("A"))
            assertEquals(2, awaitItem())

            // Starts under A (snapshot), completes under B (the coAnswers above switched).
            repo.getPrivateMessageList(page = 1)

            assertEquals("B's own initial fetch", 3, awaitItem())
            // The late piggyback (sealed for A) must NOT feed B's badge.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `requestUnreadRefresh re-fetches the count (#313)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returnsMany listOf(2, 5)

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(2, awaitItem())

            repo.requestUnreadRefresh()

            assertEquals(5, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getPrivateMessageList fetches the requested page and returns the parsed inbox`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 2) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        val parsed = PrivateMessageListPage(page = 2, totalPages = 3, items = emptyList())
        coEvery { parser.parseList(FAKE_HTML) } returns parsed

        val (repo, _) = buildRepository(hfrClient = hfrClient, parser = parser)

        assertEquals(parsed, repo.getPrivateMessageList(page = 2))
        coVerify(exactly = 1) { hfrClient.getPrivateMessageListPage(page = 2) }
    }

    @Test
    fun `getPrivateMessageThread fetches the thread page and forwards the fallback correspondent`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 1) } returns FAKE_HTML
        val threadParser = mockk<PrivateMessageThreadParser>()
        val parsed = PrivateMessageThread(
            threadId = 42,
            subject = "Sujet",
            correspondent = "Correspondant",
            messages = emptyList(),
            page = 1,
            totalPages = 1,
            canReply = true,
        )
        coEvery { threadParser.parse(FAKE_HTML, "Correspondant") } returns parsed

        val (repo, _) = buildRepository(hfrClient = hfrClient, threadParser = threadParser)

        val result = repo.getPrivateMessageThread(
            threadId = 42,
            page = 1,
            fallbackCorrespondent = "Correspondant",
        )

        assertEquals(parsed, result)
        coVerify(exactly = 1) { threadParser.parse(FAKE_HTML, "Correspondant") }
    }

    private fun summary(threadId: Int, hasUnread: Boolean) = PrivateMessageSummary(
        threadId = threadId,
        correspondent = "Correspondant",
        subject = "Sujet",
        date = Instant.EPOCH,
        hasUnread = hasUnread,
    )

    private fun buildRepository(
        hfrClient: HfrClient = mockk(relaxed = true),
        parser: PrivateMessageListParser = mockk(relaxed = true),
        threadParser: PrivateMessageThreadParser = mockk(relaxed = true),
    ): Pair<DefaultMessagesRepository, MutableSharedFlow<AuthState>> {
        // replay=1 mirrors production (the current auth state is readable at any time) : the
        // repository snapshots the session pseudo via `first()` at fetch call-time (#439).
        val authStates = MutableSharedFlow<AuthState>(replay = 1)
        val authRepository = object : AuthRepository {
            override fun observeAuthState(): Flow<AuthState> = authStates

            override suspend fun login(pseudo: String, password: String) =
                Result.failure<AuthState.Authenticated>(LoginError.Unknown("not used in this test"))

            override suspend fun logout() = Unit
        }
        val repo = DefaultMessagesRepository(
            authRepository = authRepository,
            hfrClient = hfrClient,
            parser = parser,
            threadParser = threadParser,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return repo to authStates
    }

    private companion object {
        const val FAKE_HTML = "<html><body><table></table></body></html>"
        const val FAKE_HTML_2 = "<html><body><table>v2</table></body></html>"
    }
}
