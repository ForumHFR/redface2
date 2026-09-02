package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1182 — pure distribution tests for poll result bars. */
class TopicPollResultBarsTest {

    @Test
    fun `result bars use HFR total as denominator including blank votes`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 10,
                options = listOf(
                    PollOption("Kotlin", votes = 7, percentage = 99f),
                    PollOption("Java", votes = 2, percentage = 1f),
                ),
                blankVotes = 1,
            ),
        )
        val blankVote = requireNotNull(bars.blankVote)

        assertEquals(0.7f, bars.options[0].widthFraction, FLOAT_DELTA)
        assertEquals(0.2f, bars.options[1].widthFraction, FLOAT_DELTA)
        assertEquals(0.1f, blankVote.widthFraction, FLOAT_DELTA)
        assertEquals(listOf(70, 20), bars.options.map { option -> option.percentage })
        assertEquals(10, blankVote.percentage)
        assertTrue(bars.options[0].isLeading)
        assertFalse(bars.options[1].isLeading)
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
        assertTrue(bars.options.all { option -> option.percentage == 0 })
        assertEquals(0f, blankVote.widthFraction, FLOAT_DELTA)
        assertEquals(0, blankVote.percentage)
        assertTrue(bars.options.none { option -> option.isLeading })
    }

    @Test
    fun `integer rounding uses largest remainder without exceeding one hundred`() {
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

        assertEquals(listOf(34, 33, 33), bars.options.map { option -> option.percentage })
        assertEquals(100, bars.options.sumOf { option -> option.percentage })
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

        assertEquals(listOf(30, 20), bars.options.map { option -> option.percentage })
        assertEquals(50, bars.options.sumOf { option -> option.percentage })
    }

    @Test
    fun `defensive rounding caps inconsistent rows at one hundred`() {
        val bars = calculatePollResultBars(
            poll(
                totalVotes = 10,
                options = listOf(
                    PollOption("Kotlin", votes = 9, percentage = 90f),
                    PollOption("Java", votes = 9, percentage = 90f),
                ),
                blankVotes = 9,
            ),
        )

        val displayedTotal = bars.options.sumOf { option -> option.percentage } +
            requireNotNull(bars.blankVote).percentage
        assertEquals(100, displayedTotal)
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
