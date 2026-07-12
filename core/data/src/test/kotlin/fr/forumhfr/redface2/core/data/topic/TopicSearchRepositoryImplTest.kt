package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.topic.NoTopicSearchResultsException
import fr.forumhfr.redface2.core.model.TopicSearchForm
import fr.forumhfr.redface2.core.model.TopicSearchRequest
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.HfrParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Chantier C (#546) — tests for [TopicSearchRepositoryImpl].
 *
 * The HfrClient is mocked ; the parser stays real so the response → [fr.forumhfr.redface2.core.model.Topic]
 * mapping is exercised. We assert the REQUEST construction (each form field forwarded verbatim from
 * the parsed [TopicSearchForm]) and that the response page is re-parsed as a topic. The `transsearch`
 * RESPONSE is never asserted against a real capture — none exists (see the model KDoc) — so we feed
 * the parser a known topic-page fixture as a stand-in to prove the round-trip wiring.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSearchRepositoryImplTest {

    @Test
    fun `filtered search forwards every form field and covers the whole topic (no firstnum)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(),
                topicId = any(),
                word = any(),
                spseudo = any(),
                onlyMatches = any(),
                hashCheck = any(),
                firstnum = any(),
                owntopic = any(),
                currentnum = any(),
                p = any(),
            )
        } returns fixture("topic_page_single.html")

        val topic = buildRepository(hfrClient).searchInTopic(
            TopicSearchRequest(
                form = TopicSearchForm(hashCheck = "tok", topicId = 35395, cat = 23, firstnum = 2783602),
                word = "betatest",
                spseudo = "XaTriX",
                onlyMatches = true,
            ),
        )

        // The response re-parsed as a Topic (proves the parseTopicPage round-trip wiring).
        assertEquals(999395, topic.post)
        coVerify(exactly = 1) {
            hfrClient.searchInTopic(
                cat = 23,
                topicId = 35395,
                word = "betatest",
                spseudo = "XaTriX",
                onlyMatches = true,
                hashCheck = "tok",
                // #546/#879 (per-mode semantics) — a FILTERED search lists ALL the matches of the
                // topic : firstnum stays null (with it HFR anchors ahead and misses earlier
                // matches — bug tinc 2788609), even though form.firstnum is non-null.
                firstnum = null,
                owntopic = 0,
                currentnum = null,
                p = 1,
            )
        }
    }

    @Test
    fun `non-filtered fresh anchors on the current page and carries owntopic verbatim (#879)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } returns fixture("topic_page_single.html")

        buildRepository(hfrClient).searchInTopic(
            TopicSearchRequest(
                form = TopicSearchForm(hashCheck = "tok", topicId = 7, cat = 32, firstnum = 16244, owntopic = 1),
                word = "",
                spseudo = "someone",
                onlyMatches = false,
                currentNum = "16300",
            ),
        )

        coVerify(exactly = 1) {
            // owntopic + currentnum forwarded verbatim ; #879 web parity — a NON-FILTERED fresh
            // (isStep=false) anchors on the current page : firstnum = form.firstnum.
            hfrClient.searchInTopic(
                cat = 32, topicId = 7, word = "", spseudo = "someone", onlyMatches = false,
                hashCheck = "tok", firstnum = 16244, owntopic = 1, currentnum = "16300",
                p = 1,
            )
        }
    }

    @Test
    fun `omits firstnum on a navigation step and forwards currentnum so HFR advances the cursor`() = runTest {
        // #546/#879 — a STEP request (isStep=true) ALWAYS drops firstnum, whatever the mode :
        // re-sending it re-anchors HFR on the first match and the cursor never advances
        // (live-verified stepping bug). Only the non-filtered FRESH sends firstnum (web parity).
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } returns fixture("topic_page_single.html")

        buildRepository(hfrClient).searchInTopic(
            TopicSearchRequest(
                form = TopicSearchForm(hashCheck = "tok", topicId = 35395, cat = 23, firstnum = 2783602),
                word = "betatest",
                spseudo = "",
                onlyMatches = false,
                currentNum = "2786594",
                isStep = true,
            ),
        )

        coVerify(exactly = 1) {
            hfrClient.searchInTopic(
                cat = 23, topicId = 35395, word = "betatest", spseudo = "", onlyMatches = false,
                hashCheck = "tok", firstnum = null, owntopic = 0, currentnum = "2786594",
                p = 1,
            )
        }
    }

    @Test
    fun `forwards the requested result page as p (#879 filtered pagination)`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } returns fixture("topic_page_single.html")

        buildRepository(hfrClient).searchInTopic(
            TopicSearchRequest(
                form = TopicSearchForm(hashCheck = "tok", topicId = 35395, cat = 23, firstnum = 2783602),
                word = "betatest",
                spseudo = "",
                onlyMatches = true,
                page = 3,
            ),
        )

        coVerify(exactly = 1) {
            hfrClient.searchInTopic(
                cat = 23, topicId = 35395, word = "betatest", spseudo = "", onlyMatches = true,
                hashCheck = "tok", firstnum = null, owntopic = 0, currentnum = null,
                // #879 — the historically frozen p=1 made result pages beyond the first unreachable.
                p = 3,
            )
        }
    }

    @Test
    fun `raises NoTopicSearchResultsException when HFR returns the no-result page`() {
        // Chantier B (#546) — HFR answers a « aucune réponse n'a été trouvée » page (not an HTTP error)
        // when the term/author matched nothing. The repository must detect the marker BEFORE parsing and
        // raise a typed « no result » so the ViewModel shows NoResults, not a « recherche échouée » error.
        val hfrClient = mockk<HfrClient>()
        val noResultPage = """
            <html><body>
              <div class="hop">Désolé, aucune réponse n'a été trouvée pour votre recherche.</div>
            </body></html>
        """.trimIndent()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } returns noResultPage

        assertThrows(NoTopicSearchResultsException::class.java) {
            runBlocking {
                buildRepository(hfrClient).searchInTopic(
                    TopicSearchRequest(
                        form = TopicSearchForm(hashCheck = "tok", topicId = 1, cat = 1, firstnum = 1),
                        word = "topic",
                        spseudo = "",
                        onlyMatches = false,
                    ),
                )
            }
        }
    }

    @Test
    fun `never logs the search term, the author filter or the hash_check (privacy)`() = runTest {
        // #546 finding #3 — the diagnostics trail records only presence flags + the filter mode, never
        // the free-text criteria nor the session secret. Lock that with sentinels: if any of them ever
        // leaked into a log message, this fails. (cf. the privacy KDoc on TopicSearchRepositoryImpl.)
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } returns fixture("topic_page_single.html")
        val diagnostics = DiagnosticsLog()

        buildRepository(hfrClient, diagnostics).searchInTopic(
            TopicSearchRequest(
                form = TopicSearchForm(hashCheck = "hash-secret", topicId = 35395, cat = 23, firstnum = 1),
                word = "word-secret",
                spseudo = "pseudo-secret",
                onlyMatches = true,
            ),
        )

        val sentinels = listOf("word-secret", "pseudo-secret", "hash-secret")
        val leaked = diagnostics.entries.value
            .map { it.message }
            .filter { message -> sentinels.any { message.contains(it) } }
        assertEquals("no diagnostics entry may leak the criteria or hash_check", emptyList<String>(), leaked)
        // Sanity: the search DID leave a trail (so the assertion above is not vacuously true).
        assertEquals(true, diagnostics.entries.value.any { it.message.contains("POST transsearch") })
    }

    @Test
    fun `propagates a SessionExpiredException from the network layer`() {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(), p = any(),
            )
        } throws SessionExpiredException("<redacted>")

        assertThrows(SessionExpiredException::class.java) {
            runBlocking {
                buildRepository(hfrClient).searchInTopic(
                    TopicSearchRequest(
                        form = TopicSearchForm(hashCheck = "tok", topicId = 1, cat = 1, firstnum = 1),
                        word = "x",
                        spseudo = "",
                        onlyMatches = true,
                    ),
                )
            }
        }
    }

    private fun buildRepository(
        hfrClient: HfrClient,
        diagnostics: DiagnosticsLog = DiagnosticsLog(),
    ) = TopicSearchRepositoryImpl(
        client = hfrClient,
        parser = HfrParser(),
        diagnostics = diagnostics,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
