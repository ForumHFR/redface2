package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.parser.common.HfrSelectors
import fr.forumhfr.redface2.core.parser.write.poll.PollVoteFormParser
import java.time.LocalDateTime
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Poll transformer coverage against reduced subtrees captured from live HFR pages. */
class TopicPollParserTest {
    private val parser = TopicPollParser()
    private val pollVoteFormParser = PollVoteFormParser()

    @Test
    fun `closed results expose server state and a real zero blank-vote count`() {
        val poll = parseFixture("topic_poll_closed.html")

        assertTrue(poll.closed)
        assertFalse("a closed poll must never expose the close capability", poll.canClose)
        assertEquals(0, poll.blankVotes)
        assertNull(poll.expiresAt)
    }

    @Test
    fun `owner open poll exposes native close capability`() {
        val poll = parseFixture("topic_poll_owner_open.html")

        assertFalse(poll.closed)
        assertTrue("the adjacent native close link must expose canClose", poll.canClose)
    }

    @Test
    fun `authenticated non-owner open poll fails closed without native link`() {
        val poll = parseFixture("topic_poll_form_authenticated_multi.html")

        assertFalse(poll.closed)
        assertFalse("an authenticated vote form is not proof of ownership", poll.canClose)
    }

    @Test
    fun `open form parses wall-clock expiry without inventing a blank-vote count`() {
        val poll = parseFixture("topic_poll_form_expiry.html")

        assertFalse(poll.closed)
        assertEquals(LocalDateTime.of(2027, 1, 1, 12, 0), poll.expiresAt)
        assertNull(poll.blankVotes)
    }

    @Test
    fun `open results parse wall-clock expiry and blank votes from Total block`() {
        val poll = parseFixture("topic_poll_results_blank.html")

        assertFalse(poll.closed)
        assertEquals(LocalDateTime.of(2027, 1, 1, 12, 0), poll.expiresAt)
        assertEquals(1, poll.blankVotes)
    }

    @Test
    fun `normal open form keeps optional server metadata absent`() {
        val poll = parseFixture("topic_poll_form_meteo.html")

        assertFalse(poll.closed)
        assertNull(poll.expiresAt)
        assertNull(poll.blankVotes)
    }

    @Test
    fun `blank-vote morphology preserves plural singular and zero`() {
        assertEquals(2, parser.parseBlankVotes("Total : 8 votes (2 votes blancs)"))
        assertEquals(0, parser.parseBlankVotes("Total : 8 votes (0 vote blanc)"))
        assertNull(parser.parseBlankVotes("Total : 8 votes"))
    }

    @Test
    fun `invalid wall-clock expiry degrades to null without throwing`() {
        assertNull(parser.parseExpiresAt("Ce sondage expirera le 31-13-2027 à 99:99"))
    }

    private fun parseFixture(name: String): Poll {
        val document = Jsoup.parse(fixture(name))
        val voteForm = pollVoteFormParser.parse(document)
        return requireNotNull(parser.parse(document.selectFirst(HfrSelectors.POLL), voteForm))
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Fixture not found: $name" }
            .readText()
}
