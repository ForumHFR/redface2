package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
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
    fun `forwards every form field and the filter flag to the HfrClient`() = runTest {
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
                firstnum = 2783602,
                owntopic = 0,
                currentnum = null,
            )
        }
    }

    @Test
    fun `carries the navigation cursor and owntopic verbatim`() = runTest {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(),
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
            hfrClient.searchInTopic(
                cat = 32, topicId = 7, word = "", spseudo = "someone", onlyMatches = false,
                hashCheck = "tok", firstnum = 16244, owntopic = 1, currentnum = "16300",
            )
        }
    }

    @Test
    fun `propagates a SessionExpiredException from the network layer`() {
        val hfrClient = mockk<HfrClient>()
        coEvery {
            hfrClient.searchInTopic(
                cat = any(), topicId = any(), word = any(), spseudo = any(), onlyMatches = any(),
                hashCheck = any(), firstnum = any(), owntopic = any(), currentnum = any(),
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

    private fun buildRepository(hfrClient: HfrClient) = TopicSearchRepositoryImpl(
        client = hfrClient,
        parser = HfrParser(),
        diagnostics = DiagnosticsLog(),
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }.readText()
}
