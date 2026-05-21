package fr.forumhfr.redface2.core.model.write

/**
 * Outcome of a `POST bddpost.php` for a brand-new topic (Phase 2E #149).
 *
 * Distinct from `ReplySubmitResult` because the success shape is different :
 * reply/edit return a `targetPage` for scroll restoration whereas create-topic
 * needs the brand-new `(topicId, numreponse)` to navigate to the just-created
 * thread. The two cannot be retrofitted onto the same data class without
 * polluting the reply path with create-only fields.
 *
 * `newTopicId` and `newNumreponse` are nullable on purpose : the Phase 2A
 * capture campaign did **not** record a `write_create_topic_success_response.html`
 * fixture, so until that capture lands the repository classifies success via
 * the same `ReplySubmitResponseParser` heuristic as reply/quote and forwards
 * `(null, null)` rather than guess a URL format. Navigation falls back to the
 * `CategoryRoute` refresh path in that case.
 *
 * `refreshUrl` is the raw URL HFR returned in the `<meta http-equiv=Refresh>` ;
 * we keep it around for downstream parsing once the success fixture exists,
 * but it must never be logged in `DiagnosticsLog` (contains the pseudo + topic
 * id in plain text).
 */
sealed interface NewTopicSubmitResult {
    data class Success(
        val newTopicId: Int?,
        val newNumreponse: Int?,
        val targetCat: Int,
        val targetSubcat: Int,
        val refreshUrl: String?,
    ) : NewTopicSubmitResult

    data class Failure(val reason: ReplyFailureReason) : NewTopicSubmitResult
}
