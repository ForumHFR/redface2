package fr.forumhfr.redface2.core.data.smiley

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.model.SmileyWikiKeywordsResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.smiley.SmileySearchParser
import fr.forumhfr.redface2.core.parser.smiley.SmileyWikiKeywordsParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmileyWikiKeywordsRepositoryTest {
    private val token = "[:ticento:6]"
    private val loaded = SmileyWikiKeywordsResult.Loaded(listOf("feu", "chaud"))
    private val dispatcher = UnconfinedTestDispatcher()
    private val client = mockk<HfrClient> {
        coEvery { getSmileyWikiDetail(any()) } returns "mocked Wiki response"
    }
    private val parser = mockk<SmileyWikiKeywordsParser> {
        every { parse(any(), any()) } returns loaded.keywords
    }
    private val repository = DefaultSmileyRepository(
        client, SmileySearchParser(), mockk<DiagnosticsLog>(relaxed = true), dispatcher, parser,
    )

    @Test
    fun `all builtins bypass the network`() = runTest(dispatcher) {
        BUILTIN_HFR_SMILEYS.forEach {
            assertEquals(SmileyWikiKeywordsResult.NotApplicable, repository.getWikiKeywords(it.token))
        }
        coVerify(exactly = 0) { client.getSmileyWikiDetail(any()) }
    }

    @Test
    fun `a hit is available synchronously and skips the next network call`() = runTest(dispatcher) {
        assertNull(repository.cachedWikiKeywords(token))
        assertEquals(loaded, repository.getWikiKeywords(token))
        assertEquals(loaded, repository.cachedWikiKeywords(token))
        assertEquals(loaded, repository.getWikiKeywords(token))
        coVerify(exactly = 1) { client.getSmileyWikiDetail(token) }
    }

    @Test
    fun `empty results including unknown tokens are cached`() = runTest(dispatcher) {
        every { parser.parse(any(), any()) } returns emptyList()
        repeat(2) { assertEquals(SmileyWikiKeywordsResult.Loaded(emptyList()), repository.getWikiKeywords(token)) }
        coVerify(exactly = 1) { client.getSmileyWikiDetail(token) }
    }

    @Test
    fun `network failure is not cached and can succeed on retry`() = runTest(dispatcher) {
        coEvery { client.getSmileyWikiDetail(token) } throws IOException("offline")
        assertEquals(SmileyWikiKeywordsResult.Failed, repository.getWikiKeywords(token))
        assertNull(repository.cachedWikiKeywords(token))
        coEvery { client.getSmileyWikiDetail(token) } returns "mocked Wiki response"
        assertEquals(loaded, repository.getWikiKeywords(token))
        coVerify(exactly = 2) { client.getSmileyWikiDetail(token) }
    }

    @Test
    fun `parse failure is not cached either`() = runTest(dispatcher) {
        every { parser.parse(any(), any()) } throws IllegalArgumentException("missing field")
        assertEquals(SmileyWikiKeywordsResult.Failed, repository.getWikiKeywords(token))
        assertNull(repository.cachedWikiKeywords(token))
        every { parser.parse(any(), any()) } returns loaded.keywords
        assertEquals(loaded, repository.getWikiKeywords(token))
        coVerify(exactly = 2) { client.getSmileyWikiDetail(token) }
    }

    @Test
    fun `inconsistent Wiki pages fail without caching and a corrected response can be retried`() = runTest(dispatcher) {
        val html = sharedParserFixture("smiley_wiki_detail-ticento-6.html")
        val inconsistentPages = listOf(
            html.replace("name=\"smiley0\" value=\"$token\"", "name=\"smiley0\" value=\"[:ipoule2]\""),
            html.replace("name=\"smiley0\" value=\"$token\"", "name=\"smiley0\""),
            html.replace("name=\"detail\" value=\"$token\"", "name=\"detail\" value=\"[:ipoule2]\""),
        )
        coEvery { client.getSmileyWikiDetail(token) } returnsMany (inconsistentPages + html)
        val realParserRepository = DefaultSmileyRepository(
            client, SmileySearchParser(), mockk<DiagnosticsLog>(relaxed = true), dispatcher, SmileyWikiKeywordsParser(),
        )

        repeat(inconsistentPages.size) {
            assertEquals(SmileyWikiKeywordsResult.Failed, realParserRepository.getWikiKeywords(token))
            assertNull(realParserRepository.cachedWikiKeywords(token))
        }
        val expected = SmileyWikiKeywordsResult.Loaded(
            "feu feux fire brule bruler burn chaud hot cramer cramé".split(' '),
        )
        assertEquals(expected, realParserRepository.getWikiKeywords(token))
        assertEquals(expected, realParserRepository.cachedWikiKeywords(token))
        assertEquals(expected, realParserRepository.getWikiKeywords(token))
        coVerify(exactly = inconsistentPages.size + 1) { client.getSmileyWikiDetail(token) }
    }

    @Test
    fun `LRU is bounded and reading an entry protects it from the next eviction`() = runTest(dispatcher) {
        val tokens = (0..DefaultSmileyRepository.MAX_WIKI_KEYWORDS_ENTRIES).map { "[:cache_$it]" }
        tokens.dropLast(1).forEach { repository.getWikiKeywords(it) }
        assertEquals(loaded, repository.cachedWikiKeywords(tokens[0]))
        repository.getWikiKeywords(tokens.last())
        assertNull(repository.cachedWikiKeywords(tokens[1]))
        assertEquals(loaded, repository.cachedWikiKeywords(tokens[0]))
        assertEquals(DefaultSmileyRepository.MAX_WIKI_KEYWORDS_ENTRIES, tokens.count {
            repository.cachedWikiKeywords(it) != null
        })
        repository.getWikiKeywords(tokens[1])
        coVerify(exactly = 2) { client.getSmileyWikiDetail(tokens[1]) }
    }

    @Test
    fun `cancellation propagates without caching a failure`() = runTest(dispatcher) {
        var cancelled = false
        coEvery { client.getSmileyWikiDetail(token) } coAnswers {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val job = launch { repository.getWikiKeywords(token) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(cancelled)
        assertNull(repository.cachedWikiKeywords(token))
    }

    // Same module/root layouts as HfrClientTest; reuse the capture without duplicating the fixture.
    private fun sharedParserFixture(name: String): String {
        val candidates = listOf(
            File("../parser/src/test/resources/fixtures", name),
            File("core/parser/src/test/resources/fixtures", name),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Shared parser fixture not found: $name (tried $candidates)")
        return file.readText()
    }
}
