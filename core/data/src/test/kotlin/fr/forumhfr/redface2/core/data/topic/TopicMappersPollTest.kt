package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #697 — dedicated poll persistence coverage (the poll DTO round-trip had no test before the
 * `resultsAvailable` flag landed) : the flag must survive the cache round-trip, and legacy rows
 * (JSON written before the flag existed) must decode as « résultats disponibles ».
 */
class TopicMappersPollTest {

    private fun topicWith(poll: Poll?): Topic = Topic(
        cat = 13,
        post = 44713,
        subcat = 432,
        title = "TU Météo",
        posts = emptyList(),
        page = 1,
        totalPages = 3811,
        isFirstPostOwner = false,
        poll = poll,
    )

    @Test
    fun `form-shape poll survives the cache round-trip with resultsAvailable false`() {
        val formPoll = Poll(
            question = "Qui doit être TT cet été ?",
            options = listOf("Thoulisse", "Wurst").map { PollOption(it, votes = 0, percentage = 0f) },
            multipleChoice = false,
            totalVotes = 0,
            hasVoted = false,
            resultsAvailable = false,
        )

        val (entity, posts) = TopicMappers.toEntities(topicWith(formPoll), Instant.EPOCH, FetchMode.ANONYMOUS)
        val restored = TopicMappers.toDomain(entity, posts)

        val poll = requireNotNull(restored.poll)
        assertFalse(poll.resultsAvailable)
        assertEquals(formPoll.options, poll.options)
        assertEquals(formPoll.question, poll.question)
    }

    @Test
    fun `legacy cache row without the flag decodes as results available`() {
        // A pollJson written BEFORE #697 : no `resultsAvailable` key at all. The DTO default (true)
        // must make it decode as the results shape — the only shape that existed back then.
        val (entity, posts) = TopicMappers.toEntities(
            topicWith(
                Poll(
                    question = "q",
                    options = listOf(PollOption("a", votes = 3, percentage = 50f)),
                    multipleChoice = true,
                    totalVotes = 6,
                    hasVoted = false,
                ),
            ),
            Instant.EPOCH,
            FetchMode.ANONYMOUS,
        )
        val legacyEntity = entity.copy(
            pollJson = entity.pollJson!!.replace(Regex(""","resultsAvailable":(true|false)"""), ""),
        )

        val restored = TopicMappers.toDomain(legacyEntity, posts)

        assertTrue(requireNotNull(restored.poll).resultsAvailable)
    }

    @Test
    fun `maxSelections survives the cache round-trip (#779)`() {
        val poll = Poll(
            question = "Space X en bourse ?",
            options = listOf("monter", "crasher").map { PollOption(it, votes = 0, percentage = 0f) },
            multipleChoice = true,
            totalVotes = 0,
            hasVoted = false,
            resultsAvailable = false,
            maxSelections = 2,
        )

        val (entity, posts) = TopicMappers.toEntities(topicWith(poll), Instant.EPOCH, FetchMode.ANONYMOUS)
        val restored = TopicMappers.toDomain(entity, posts)

        assertEquals(2, requireNotNull(restored.poll).maxSelections)
    }

    @Test
    fun `legacy cache row without maxSelections decodes as null, not an invented 1 (#779)`() {
        // A pollJson written BEFORE #779 : no `maxSelections` key at all. The DTO default (null)
        // must keep the limit unknown — coercing to 1 would falsely cap a cached multi-choice poll.
        val (entity, posts) = TopicMappers.toEntities(
            topicWith(
                Poll(
                    question = "q",
                    options = listOf(PollOption("a", votes = 0, percentage = 0f)),
                    multipleChoice = true,
                    totalVotes = 0,
                    hasVoted = false,
                    maxSelections = 3,
                ),
            ),
            Instant.EPOCH,
            FetchMode.ANONYMOUS,
        )
        val legacyEntity = entity.copy(
            pollJson = entity.pollJson!!.replace(Regex(""","maxSelections":(\d+|null)"""), ""),
        )

        val restored = TopicMappers.toDomain(legacyEntity, posts)

        assertNull(requireNotNull(restored.poll).maxSelections)
    }
}
