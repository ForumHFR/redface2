package fr.forumhfr.redface2.core.data.search

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.search.SearchCategoryScope
import fr.forumhfr.redface2.core.model.search.SearchRequest
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
            )
        }
    }

    @Test
    fun `category-scoped search forwards the bare integer id to the HfrClient`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchTopics(query = any(), cat = any(), page = any(), date = any())
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
            hfrClient.searchTopics(query = any(), cat = 10, page = any(), date = any())
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
            hfrClient.searchTopics(any(), any(), any(), any())
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
            hfrClient.searchTopics(any(), any(), any(), any())
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

