package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PollCloseResult
import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.model.write.PollVoteResult

/** Domain contract for one non-idempotent HFR poll vote submission. */
interface PollVoteRepository {
    suspend fun submitPollVote(
        form: PollVoteForm,
        selectedChoices: Set<PollVoteChoice>,
    ): PollVoteResult

    suspend fun submitBlankVote(form: PollVoteForm): PollVoteResult

    /**
     * #1201 — close the poll of topic [topicId] in category [cat] (owner-only, irreversible). The
     * gate is enforced by HFR, not here : an unauthorised caller simply gets a [PollCloseResult.Failure].
     */
    suspend fun closePoll(cat: Int, topicId: Int): PollCloseResult
}
