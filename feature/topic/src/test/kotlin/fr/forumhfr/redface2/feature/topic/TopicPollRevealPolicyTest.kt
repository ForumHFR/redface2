package fr.forumhfr.redface2.feature.topic

import fr.forumhfr.redface2.core.model.write.PollVoteForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1170 — pure policy tests for the controlled poll-card expansion state. */
class TopicPollRevealPolicyTest {

    @Test
    fun `the complete reveal matrix preserves manual priority and requires a live open form`() {
        val manualChoices = listOf<Boolean?>(null, true, false)
        repeat(manualChoices.size * BOOLEAN_COMBINATIONS) { caseIndex ->
            val manualExpanded = manualChoices[caseIndex / BOOLEAN_COMBINATIONS]
            val flags = caseIndex % BOOLEAN_COMBINATIONS
            val pollsExpandedDefault = flags.hasBit(0)
            val expandUnansweredPolls = flags.hasBit(1)
            val hasLiveHash = flags.hasBit(2)
            val pollClosed = flags.hasBit(3)
            val expected = when (manualExpanded) {
                null -> pollsExpandedDefault ||
                    (expandUnansweredPolls && hasLiveHash && !pollClosed)
                else -> manualExpanded
            }

            assertEquals(
                "manual=$manualExpanded, default=$pollsExpandedDefault, " +
                    "unanswered=$expandUnansweredPolls, liveHash=$hasLiveHash, " +
                    "closed=$pollClosed",
                expected,
                resolvePollRevealed(
                    manualExpanded = manualExpanded,
                    pollsExpandedDefault = pollsExpandedDefault,
                    expandUnansweredPolls = expandUnansweredPolls,
                    pollVoteForm = form(hashCheck = if (hasLiveHash) "live-token" else ""),
                    pollClosed = pollClosed,
                ),
            )
        }
    }

    @Test
    fun `collapsed poll with opt-in and a live form is expanded`() {
        assertTrue(
            resolvePollRevealed(
                manualExpanded = null,
                pollsExpandedDefault = false,
                expandUnansweredPolls = true,
                pollVoteForm = form(hashCheck = "live-token"),
                pollClosed = false,
            ),
        )
    }

    @Test
    fun `closed poll is not auto-expanded by the unanswered opt-in`() {
        assertFalse(
            resolvePollRevealed(
                manualExpanded = null,
                pollsExpandedDefault = false,
                expandUnansweredPolls = true,
                pollVoteForm = form(hashCheck = "live-token"),
                pollClosed = true,
            ),
        )
    }

    @Test
    fun `consumed form with an empty hash is not auto-expanded`() {
        assertFalse(
            resolvePollRevealed(
                manualExpanded = null,
                pollsExpandedDefault = false,
                expandUnansweredPolls = true,
                pollVoteForm = form(hashCheck = ""),
                pollClosed = false,
            ),
        )
    }

    @Test
    fun `manual collapse wins over both expansion preferences and a live form`() {
        assertFalse(
            resolvePollRevealed(
                manualExpanded = false,
                pollsExpandedDefault = true,
                expandUnansweredPolls = true,
                pollVoteForm = form(hashCheck = "live-token"),
                pollClosed = false,
            ),
        )
    }

    private fun form(hashCheck: String) = PollVoteForm(
        hashCheck = hashCheck,
        hiddenFields = emptyMap(),
        choices = emptyList(),
        multipleChoice = false,
        maxSelections = 1,
    )

    private fun Int.hasBit(bit: Int): Boolean = this and (1 shl bit) != 0

    private companion object {
        const val BOOLEAN_COMBINATIONS = 16
    }
}
