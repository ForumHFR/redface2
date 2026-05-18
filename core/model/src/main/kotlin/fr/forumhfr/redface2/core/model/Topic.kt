package fr.forumhfr.redface2.core.model

data class Topic(
    val cat: Int,
    val post: Int,
    /**
     * Sub-category id. Required by HFR's `message.php` / `bddpost.php` write endpoints
     * (cf. `docs/specs/protocol-hfr.md` § POST `bddpost.php`). Sentinel value [SUBCAT_UNKNOWN]
     * means the row was read from a v3 cache that pre-dates subcat persistence; the topic
     * is then read-only until a live refresh produces a real id. It is never transmitted
     * to HFR — write flows guard against it via [hasSubcat].
     */
    val subcat: Int,
    val title: String,
    val posts: List<Post>,
    val page: Int,
    val totalPages: Int,
    val isFirstPostOwner: Boolean,
    val poll: Poll?,
) {
    val hasSubcat: Boolean get() = subcat != SUBCAT_UNKNOWN

    companion object {
        const val SUBCAT_UNKNOWN: Int = -1
    }
}

data class Poll(
    val question: String,
    val options: List<PollOption>,
    val multipleChoice: Boolean,
    val totalVotes: Int,
    val hasVoted: Boolean,
)

data class PollOption(
    val text: String,
    val votes: Int,
    val percentage: Float,
)
