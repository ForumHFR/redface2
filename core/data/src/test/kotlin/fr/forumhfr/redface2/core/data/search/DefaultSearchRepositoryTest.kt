package fr.forumhfr.redface2.core.data.search

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.error.HfrServerException
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchRequest
import fr.forumhfr.redface2.core.model.search.SearchTextScope
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.search.SearchResultParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2G-A (#150 partiel) — tests for [DefaultSearchRepository].
 *
 * The HfrClient is mocked via MockK ; the parser stays real so the wire-shape
 * mapping is exercised end-to-end. Fixtures live in `:core:parser/src/test/resources/`,
 * not in this module, so the test uses minimal synthetic HTML for each branch
 * (the parser's own test suite covers the four canonical fixture shapes).
 *
 * Focus of this test class : repository contract — query wiring, date injection
 * from the Clock, diagnostics redaction, and the IOException URL-leak guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSearchRepositoryTest {

    @Test
    fun `global search forwards an empty cat to the HfrClient`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(
                query = "kotlin",
                cat = null,
                page = 1,
                date = any(),
                textScope = SearchTextScope.TitlesAndPosts,
            )
        } returns NO_RESULTS_HTML

        val repo = buildRepository(hfrClient = hfrClient)
        repo.search(SearchRequest(query = "kotlin"))

        coVerify(exactly = 1) {
            hfrClient.searchTopics(
                query = "kotlin",
                cat = null,
                page = 1,
                date = any(),
                textScope = SearchTextScope.TitlesAndPosts,
            )
        }
    }

    @Test
    fun `category-scoped search forwards the bare integer id to the HfrClient`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(
                query = any(),
                cat = any(),
                page = any(),
                date = any(),
                textScope = any(),
            )
        } returns NO_RESULTS_HTML

        val repo = buildRepository(hfrClient = hfrClient)
        repo.search(
            SearchRequest(
                query = "kotlin",
                category = SearchCategoryScope.Category(id = 10, name = "Programmation"),
            ),
        )

        // The repository passes the bare integer ; the HfrClient is responsible for the
        // `10*hfr.inc` wire encoding (covered by its own tests).
        coVerify(exactly = 1) {
            hfrClient.searchTopics(
                query = any(),
                cat = 10,
                page = any(),
                date = any(),
                textScope = any(),
            )
        }
    }

    @Test
    fun `text scope is forwarded to the HfrClient`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(
                query = "kotlin",
                cat = null,
                page = 1,
                date = any(),
                textScope = SearchTextScope.PostsOnly,
            )
        } returns NO_RESULTS_HTML

        val repo = buildRepository(hfrClient = hfrClient)
        repo.search(SearchRequest(query = "kotlin", textScope = SearchTextScope.PostsOnly))

        coVerify(exactly = 1) {
            hfrClient.searchTopics(
                query = "kotlin",
                cat = null,
                page = 1,
                date = any(),
                textScope = SearchTextScope.PostsOnly,
            )
        }
    }

    @Test
    fun `date passed to HfrClient comes from the injected Clock`() = runTest {
        val hfrClient = mockk<HfrClient>()
        val dateSlot = slot<java.time.LocalDate>()
        coEvery {
            hfrClient.searchTopics(
                query = any(),
                cat = any(),
                page = any(),
                date = capture(dateSlot),
                textScope = any(),
            )
        } returns NO_RESULTS_HTML

        // Pin the clock to 2026-05-22 so the test is reproducible on any runner clock.
        val pinned = Clock.fixed(Instant.parse("2026-05-22T10:00:00Z"), ZoneOffset.UTC)
        val repo = buildRepository(hfrClient = hfrClient, clock = pinned)
        repo.search(SearchRequest(query = "x"))

        assertEquals(java.time.LocalDate.of(2026, 5, 22), dateSlot.captured)
    }

    @Test
    fun `no-results HTML maps to an empty SearchResultPage`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(any(), any(), any(), any(), any())
        } returns NO_RESULTS_HTML

        val repo = buildRepository(hfrClient = hfrClient)
        val page = repo.search(SearchRequest(query = "xqzkbm9wj4abc"))

        assertEquals(emptyList<Any>(), page.topics)
        assertEquals(emptyList<Any>(), page.pivotCategories)
        assertEquals("xqzkbm9wj4abc", page.query)
    }

    @Test
    fun `IOException from HfrClient is rebranded without leaking the query URL`() = runTest {
        val hfrClient = mockk<HfrClient>()
        // Mimic the exact message shape that `executeAuthenticatedHtml` produces.
        coEvery {
            hfrClient.searchTopics(any(), any(), any(), any(), any())
        } throws IOException(
            "HFR returned 500 for https://forum.hardware.fr/forum1.php?recherches=1&cat=&search=secret_term&...",
        )

        val repo = buildRepository(hfrClient = hfrClient)
        val thrown = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                repo.search(SearchRequest(query = "secret_term"))
            }
        }

        // The repository must NOT propagate the original `for <url>` suffix which contains
        // `search=secret_term`. Diagnostics screenshots of this exception should be safe.
        assertFalse(
            "expected the URL portion to be stripped from the message, got <${thrown.message}>",
            thrown.message!!.contains("forum1.php"),
        )
        assertFalse(
            "expected the user query to NOT appear in the rebranded exception, got <${thrown.message}>",
            thrown.message!!.contains("secret_term"),
        )
        // Still informative : status code + the HFR prefix survive.
        assertTrue(thrown.message!!.contains("500"))
    }

    @Test
    fun `SessionExpiredException is rebranded without leaking the URL or query`() = runTest {
        val hfrClient = mockk<HfrClient>()
        // `SessionExpiredException` extends IOException and its message embeds the
        // final URL via the « final URL was » prefix (not « for »), so the generic
        // substringBefore(" for ") would let the URL through. Make sure the explicit
        // SessionExpiredException branch redacts it.
        coEvery {
            hfrClient.searchTopics(any(), any(), any(), any(), any())
        } throws SessionExpiredException(
            "https://forum.hardware.fr/forum1.php?recherches=1&cat=&search=secret_term&...",
        )

        val repo = buildRepository(hfrClient = hfrClient)
        val thrown = assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { repo.search(SearchRequest(query = "secret_term")) }
        }

        assertFalse(
            "expected the URL portion to be stripped from the SessionExpired path, got <${thrown.message}>",
            thrown.message!!.contains("forum1.php"),
        )
        assertFalse(
            "expected the user query to NOT survive the SessionExpired rebrand, got <${thrown.message}>",
            thrown.message!!.contains("secret_term"),
        )
        assertTrue(
            "expected the message to mention « session expired », got <${thrown.message}>",
            thrown.message!!.contains("session expired"),
        )
        // #324 — the TYPE must survive the redaction so downstream classification
        // (classifyHfrError → Other, never Network) still sees the session expiry.
        assertTrue(
            "expected the SessionExpiredException type to be preserved, got ${thrown::class.simpleName}",
            thrown is SessionExpiredException,
        )
    }

    @Test
    fun `HfrServerException traverses with its code preserved and its URL redacted`() = runTest {
        // #324 — without the dedicated catch branch, the generic IOException re-wrap would
        // strip the type and a 5xx HFR outage would be classified as a network cut by
        // SearchViewModel. The URL still must not survive (it carries `search=<query>`).
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(any(), any(), any(), any(), any())
        } throws HfrServerException(
            code = 500,
            url = "https://forum.hardware.fr/forum1.php?recherches=1&cat=&search=secret_term&...",
        )

        val repo = buildRepository(hfrClient = hfrClient)
        val thrown = assertThrows(HfrServerException::class.java) {
            kotlinx.coroutines.runBlocking { repo.search(SearchRequest(query = "secret_term")) }
        }

        assertEquals(500, thrown.code)
        assertFalse(
            "expected the URL portion to be redacted, got <${thrown.message}>",
            thrown.message!!.contains("forum1.php"),
        )
        assertFalse(
            "expected the user query to NOT survive the redaction, got <${thrown.message}>",
            thrown.message!!.contains("secret_term"),
        )
    }

    @Test
    fun `resolveSearchResultPage parses the page from the redirect Location`() = runTest {
        val hfrClient = mockk<HfrClient>()
        // Live-proven Location shape (#277, 2026-06-10) : relative pretty URL + fragment.
        coEvery {
            hfrClient.resolveTopicPageUrl(cat = 23, post = 35421, numreponse = 2786758)
        } returns "/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758"

        val repo = buildRepository(hfrClient = hfrClient)

        assertEquals(3, repo.resolveSearchResultPage(cat = 23, post = 35421, numreponse = 2786758))
    }

    @Test
    fun `resolveSearchResultPage returns null when the client found no redirect`() = runTest {
        val hfrClient = mockk<HfrClient>()
        // HfrClient already degrades non-redirect / no-Location / IOException to null.
        coEvery { hfrClient.resolveTopicPageUrl(any(), any(), any()) } returns null

        val repo = buildRepository(hfrClient = hfrClient)

        assertNull(repo.resolveSearchResultPage(cat = 23, post = 35421, numreponse = 2786758))
    }

    @Test
    fun `resolveSearchResultPage returns null when the Location is not parsable`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery { hfrClient.resolveTopicPageUrl(any(), any(), any()) } returns "/login.php"

        val repo = buildRepository(hfrClient = hfrClient)

        assertNull(repo.resolveSearchResultPage(cat = 23, post = 35421, numreponse = 2786758))
    }

    private fun buildRepository(
        hfrClient: HfrClient = mockk(),
        clock: Clock = Clock.fixed(Instant.parse("2026-05-22T10:00:00Z"), ZoneOffset.UTC),
    ) = DefaultSearchRepository(
        hfrClient = hfrClient,
        parser = SearchResultParser(),
        diagnostics = DiagnosticsLog(),
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private companion object {
        // Minimal valid no-results HTML — matches the `search_no_results.html` fixture
        // shape without duplicating the full template. The parser detects this via the
        // `.hop` div + the canonical « Désolé, aucune réponse n'a été trouvée » string.
        const val NO_RESULTS_HTML = """
            <html><body>
              <div class="container">
                <div class="mesdiscussions" id="mesdiscussions">
                  <div class="hop">Désolé, aucune réponse n'a été trouvée !</div>
                </div>
              </div>
            </body></html>
        """
    }
}
