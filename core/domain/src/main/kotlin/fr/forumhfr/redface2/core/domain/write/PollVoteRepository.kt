package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.model.write.PollVoteResult

/** Domain contract for one non-idempotent HFR poll vote submission. */
interface PollVoteRepository {
    suspend fun submitPollVote(
        form: PollVoteForm,
        selectedChoices: Set<PollVoteChoice>,
    ): PollVoteResult
}
