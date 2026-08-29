package fr.forumhfr.redface2.core.model.write

/** Result of one non-idempotent vote submission to HFR. */
sealed interface PollVoteResult {
    data object Accepted : PollVoteResult
    data object AlreadyVoted : PollVoteResult
    data class Failed(val reason: PollVoteFailureReason) : PollVoteResult
}

/**
 * Typed poll-vote failures.
 *
 * All reasons except [UnexpectedResponse] are produced by repository guards before any POST.
 * [UnexpectedResponse] is reserved for an HTTP 200 response whose HFR message is not recognised.
 */
enum class PollVoteFailureReason {
    InvalidHashCheck,
    EmptySelection,
    InvalidSelection,
    TooManySelections,
    MalformedForm,
    UnexpectedResponse,
}
