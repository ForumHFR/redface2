package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure distribution tests for poll result bars. */
class TopicPollResultBarsTest {

    @Test
    fun `result bars use parsed HFR percentages before recomputing`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 176,
                options = listOf(
                    PollOption("Kotlin", votes = 34, percentage = 20.7f),
                    PollOption("Java", votes = 33, percentage = 20.1f),
                ),
                blankVotes = 12,
            ),
        )
        val blankVote = requireNotNull(bars.blankVote)

        assertEquals(0.207f, bars.options[0].widthFraction, FLOAT_DELTA)
        assertEquals(0.201f, bars.options[1].widthFraction, FLOAT_DELTA)
        assertEquals(12f / 176f, blankVote.widthFraction, FLOAT_DELTA)
        assertEquals(listOf("20.7", "20.1"), bars.options.map { option -> option.percentage })
        assertEquals("6.8", blankVote.percentage)
        assertTrue(bars.options[0].isLeading)
        assertFalse(bars.options[1].isLeading)
    }

    @Test
    fun `result bars fall back to option-vote recomputation when parsed percentage is absent`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 176,
                options = listOf(
                    PollOption("Kotlin", votes = 33, percentage = 0f),
                    PollOption("Java", votes = 131, percentage = 0f),
                ),
                blankVotes = 12,
            ),
        )

        assertEquals(listOf("20.1", "79.9"), bars.options.map { option -> option.percentage })
        assertEquals(33f / 164f, bars.options[0].widthFraction, FLOAT_DELTA)
        assertEquals(131f / 164f, bars.options[1].widthFraction, FLOAT_DELTA)
    }

    @Test
    fun `zero total keeps all result bars empty`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 0,
                options = listOf(
                    PollOption("Kotlin", votes = 0, percentage = 0f),
                    PollOption("Java", votes = 0, percentage = 0f),
                ),
                blankVotes = 0,
            ),
        )
        val blankVote = requireNotNull(bars.blankVote)

        assertTrue(bars.options.all { option -> option.widthFraction == 0f })
        assertTrue(bars.options.all { option -> option.percentage == "0" })
        assertEquals(0f, blankVote.widthFraction, FLOAT_DELTA)
        assertEquals("0", blankVote.percentage)
        assertTrue(bars.options.none { option -> option.isLeading })
    }

    @Test
    fun `fractional parsed percentages keep one decimal`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 3,
                options = listOf(
                    PollOption("Kotlin", votes = 1, percentage = 33.3f),
                    PollOption("Java", votes = 1, percentage = 33.3f),
                    PollOption("Rust", votes = 1, percentage = 33.3f),
                ),
            ),
        )

        assertEquals(listOf("33.3", "33.3", "33.3"), bars.options.map { option -> option.percentage })
    }

    @Test
    fun `displayed percentage sum can stay below one hundred when some votes are unknown`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 10,
                options = listOf(
                    PollOption("Kotlin", votes = 3, percentage = 30f),
                    PollOption("Java", votes = 2, percentage = 20f),
                ),
            ),
        )

        assertEquals(listOf("30", "20"), bars.options.map { option -> option.percentage })
    }

    @Test
    fun `defensive parsed percentage caps inconsistent rows at one hundred`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 10,
                options = listOf(
                    PollOption("Kotlin", votes = 9, percentage = 150f),
                    PollOption("Java", votes = 1, percentage = 10f),
                ),
            ),
        )

        assertEquals("100", bars.options[0].percentage)
        assertEquals(1f, bars.options[0].widthFraction, FLOAT_DELTA)
    }

    @Test
    fun `blank vote row is absent when HFR did not expose its counter`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 2,
                options = listOf(PollOption("Kotlin", votes = 2, percentage = 100f)),
                blankVotes = null,
            ),
        )

        assertNull(bars.blankVote)
    }

    private fun poll(
        totalVotes: Int,
        options: List<PollOption>,
        blankVotes: Int? = null,
    ): Poll = Poll(
        question = "Quel langage préférez-vous ?",
        options = options,
        multipleChoice = false,
        totalVotes = totalVotes,
        hasVoted = true,
        resultsAvailable = true,
        blankVotes = blankVotes,
    )

    private companion object {
        const val FLOAT_DELTA = 0.0001f
    }
}
