package fr.forumhfr.redface2.core.data.messages

import app.cash.turbine.test
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.LoginError
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.messages.PrivateMessageThreadPage
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `markThreadRead decrements the observed count and clears it on the last unread (#453)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 1

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(1, awaitItem())

            // Reading the LAST unread conversation must clear the badge immediately (#453), with no
            // second network fetch (the count came from page 1 only once).
            repo.markThreadRead(threadId = 42)

            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { hfrClient.getPrivateMessageListPage(page = 1) }
    }

    @Test
    fun `markThreadRead twice on the same thread decrements only once (#453)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 2

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(2, awaitItem())

            repo.markThreadRead(threadId = 7)
            assertEquals(1, awaitItem())

            // Re-opening the same conversation must NOT subtract again (idempotent per thread until
            // the next authoritative network count).
            repo.markThreadRead(threadId = 7)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a fresh network count resets the local read-decrements (#453)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } returns FAKE_HTML
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returnsMany listOf(1, 3)

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            assertEquals(1, awaitItem())

            repo.markThreadRead(threadId = 1)
            assertEquals(0, awaitItem())

            // A real refresh is authoritative : it supersedes the local decrement (the server count
            // is the source of truth ; the optimistic subtraction was only a stop-gap).
            repo.requestUnreadRefresh()
            assertEquals(3, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a read racing the cold-start fetch is preserved by the initial count (#453 codex)`() = runTest {
        // Codex review — at cold start the user may open an unread MP before the very first
        // `fetchUnreadCount()` resolves. The initial network count must NOT discard that read (HFR
        // has no server-side read flag, so the count can't reflect it anyway), otherwise the badge
        // would snap back to the pre-read total. Here the cold-start fetch is held on a gate, the
        // read lands first, then the fetch resolves : 3 - 1 must be displayed, not a reset to 3.
        val hfrClient = mockk<HfrClient>()
        val gate = CompletableDeferred<Unit>()
        coEvery { hfrClient.getPrivateMessageListPage(page = 1) } coAnswers {
            gate.await()
            FAKE_HTML
        }
        val parser = mockk<PrivateMessageListParser>()
        coEvery { parser.countUnread(FAKE_HTML) } returns 3

        val (repo, authStates) = buildRepository(hfrClient = hfrClient, parser = parser)

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Authenticated("xaat"))
            // The cold-start fetch is suspended on the gate ; the read races ahead of it.
            repo.markThreadRead(threadId = 11)
            // Now the initial fetch resolves : it adopts the count but keeps the racing read.
            gate.complete(Unit)

            assertEquals(
                "the initial count must subtract the read that raced it, not reset to the raw total",
                2,
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `markThreadRead is inert while anonymous (#453)`() = runTest {
        val (repo, authStates) = buildRepository()

        repo.observeUnreadMpCount().test {
            authStates.emit(AuthState.Anonymous)
            assertNull(awaitItem())

            repo.markThreadRead(threadId = 5)
            expectNoEvents()
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
    fun `getPrivateMessageThread emits network and caches a matching terminal page`() = runTest {
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

        val cache = PrivateMessageThreadSessionCache()
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated(" XaaT "))

        val result = repo.getPrivateMessageThread(
            threadId = 42,
            page = 1,
            fallbackCorrespondent = "Correspondant",
        ).toList()

        assertEquals(
            listOf(PrivateMessageThreadPage(parsed, PrivateMessageThreadPage.Source.NETWORK)),
            result,
        )
        val stamp = cache.capture("xaat")
        assertEquals(parsed, cache.read(stamp, threadId = 42, page = 1))
        coVerify(exactly = 1) { threadParser.parse(FAKE_HTML, "Correspondant") }
    }

    @Test
    fun `a session cache hit emits immediately then is always revalidated by network`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 1) } returns FAKE_HTML
        val cached = thread(subject = "cached")
        val refreshed = thread(subject = "refreshed")
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } returns refreshed
        val cache = PrivateMessageThreadSessionCache()
        val stamp = cache.capture("xaat")
        cache.write(stamp, threadId = 42, page = 1, thread = cached)
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated("XaaT"))

        val result = repo.getPrivateMessageThread(threadId = 42, page = 1).toList()

        assertEquals(
            listOf(
                PrivateMessageThreadPage(cached, PrivateMessageThreadPage.Source.SESSION_CACHE),
                PrivateMessageThreadPage(refreshed, PrivateMessageThreadPage.Source.NETWORK),
            ),
            result,
        )
        coVerify(exactly = 1) { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 1) }
    }

    @Test
    fun `warm page probe uses the cache account and current generation seal`() {
        val cache = PrivateMessageThreadSessionCache()
        cache.write(
            cache.capture("xaat"),
            threadId = 42,
            page = 2,
            thread = thread(page = 2, totalPages = 3),
        )
        val (repo, _) = buildRepository(threadSessionCache = cache)

        assertTrue(repo.isPrivateMessageThreadPageWarm(" XaaT ", threadId = 42, page = 2))
        assertFalse(repo.isPrivateMessageThreadPageWarm("bob", threadId = 42, page = 2))
        assertFalse(repo.isPrivateMessageThreadPageWarm("xaat", threadId = 42, page = 1))

        cache.clearAndAdvanceGeneration()

        assertFalse(repo.isPrivateMessageThreadPageWarm("xaat", threadId = 42, page = 2))
    }

    @Test
    fun `a redirected or clamped response is emitted but never stored under the requested key`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 9) } returns FAKE_HTML
        val clamped = thread(threadId = 99, page = 7, totalPages = 7)
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } returns clamped
        val cache = PrivateMessageThreadSessionCache()
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated("xaat"))

        val result = repo.getPrivateMessageThread(threadId = 42, page = 9).toList()

        assertEquals(
            listOf(PrivateMessageThreadPage(clamped, PrivateMessageThreadPage.Source.NETWORK)),
            result,
        )
        assertNull(cache.read(cache.capture("xaat"), threadId = 42, page = 9))
    }

    @Test
    fun `a response landing after an account switch is neither cached nor emitted`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 1) } returns FAKE_HTML
        val threadParser = mockk<PrivateMessageThreadParser>()
        lateinit var authStates: MutableSharedFlow<AuthState>
        coEvery { threadParser.parse(FAKE_HTML, null) } coAnswers {
            authStates.emit(AuthState.Authenticated("bob"))
            thread()
        }
        val cache = PrivateMessageThreadSessionCache()
        val (repo, states) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates = states
        authStates.emit(AuthState.Authenticated("alice"))

        val result = repo.getPrivateMessageThread(threadId = 42, page = 1).toList()

        assertEquals(emptyList<PrivateMessageThreadPage>(), result)
        assertNull(cache.read(cache.capture("alice"), threadId = 42, page = 1))
        assertNull(cache.read(cache.capture("bob"), threadId = 42, page = 1))
    }

    @Test
    fun `a response landing after generation advance is neither cached nor emitted`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 1) } returns FAKE_HTML
        val cache = PrivateMessageThreadSessionCache()
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } answers {
            cache.clearAndAdvanceGeneration()
            thread()
        }
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated("alice"))

        val result = repo.getPrivateMessageThread(threadId = 42, page = 1).toList()

        assertEquals(emptyList<PrivateMessageThreadPage>(), result)
        assertNull(cache.read(cache.capture("alice"), threadId = 42, page = 1))
    }

    @Test
    fun `prefetch stores a matching terminal page in the session cache`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 2) } returns FAKE_HTML
        val parsed = thread(page = 2, totalPages = 3)
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } returns parsed
        val cache = PrivateMessageThreadSessionCache()
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated(" XaaT "))

        repo.prefetchPrivateMessageThread(threadId = 42, page = 2)

        assertEquals(parsed, cache.read(cache.capture("xaat"), threadId = 42, page = 2))
        coVerify(exactly = 1) { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 2) }
    }

    @Test
    fun `prefetch skips a page already present in the session cache`() = runTest {
        val hfrClient = mockk<HfrClient>()
        val cache = PrivateMessageThreadSessionCache()
        val cached = thread(page = 2, totalPages = 3)
        cache.write(cache.capture("xaat"), threadId = 42, page = 2, thread = cached)
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated("xaat"))

        repo.prefetchPrivateMessageThread(threadId = 42, page = 2)

        coVerify(exactly = 0) { hfrClient.getPrivateMessageThreadPage(any(), any()) }
        assertEquals(cached, cache.read(cache.capture("xaat"), threadId = 42, page = 2))
    }

    @Test
    fun `prefetch never stores a redirected or clamped response under the target key`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 9) } returns FAKE_HTML
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } returns thread(threadId = 99, page = 7, totalPages = 7)
        val cache = PrivateMessageThreadSessionCache()
        val (repo, authStates) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates.emit(AuthState.Authenticated("xaat"))

        repo.prefetchPrivateMessageThread(threadId = 42, page = 9)

        assertNull(cache.read(cache.capture("xaat"), threadId = 42, page = 9))
    }

    @Test
    fun `prefetch response landing after an account switch cannot repopulate the cache`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.getPrivateMessageThreadPage(threadId = 42, page = 2) } returns FAKE_HTML
        lateinit var authStates: MutableSharedFlow<AuthState>
        val threadParser = mockk<PrivateMessageThreadParser>()
        coEvery { threadParser.parse(FAKE_HTML, null) } coAnswers {
            authStates.emit(AuthState.Authenticated("bob"))
            thread(page = 2, totalPages = 3)
        }
        val cache = PrivateMessageThreadSessionCache()
        val (repo, states) = buildRepository(
            hfrClient = hfrClient,
            threadParser = threadParser,
            threadSessionCache = cache,
        )
        authStates = states
        authStates.emit(AuthState.Authenticated("alice"))

        repo.prefetchPrivateMessageThread(threadId = 42, page = 2)

        assertNull(cache.read(cache.capture("alice"), threadId = 42, page = 2))
        assertNull(cache.read(cache.capture("bob"), threadId = 42, page = 2))
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
        threadSessionCache: PrivateMessageThreadSessionCache = PrivateMessageThreadSessionCache(),
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
            threadSessionCache = threadSessionCache,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        return repo to authStates
    }

    private companion object {
        const val FAKE_HTML = "<html><body><table></table></body></html>"
        const val FAKE_HTML_2 = "<html><body><table>v2</table></body></html>"
    }

    private fun thread(
        threadId: Int = 42,
        subject: String = "Sujet",
        page: Int = 1,
        totalPages: Int = 1,
    ) = PrivateMessageThread(
        threadId = threadId,
        subject = subject,
        correspondent = "Correspondant",
        messages = emptyList(),
        page = page,
        totalPages = totalPages,
        canReply = true,
    )
}
