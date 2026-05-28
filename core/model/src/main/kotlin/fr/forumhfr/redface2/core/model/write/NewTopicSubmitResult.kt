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
 * `newTopicId` and `newNumreponse` are extracted from the `<meta refresh>` URL
 * of the `bddpost.php` success page, whose `…/sujet_{topicId}_{page}.htm#t{numreponse}`
 * shape is identical across reply / quote / edit / create-topic — validated against
 * the real `write_reply_success_response.html` / `write_quote_success_response.html`
 * fixtures (#206). They stay nullable because HFR may, in an unexpected response,
 * omit the segment ; in that case navigation falls back to the `CategoryRoute`
 * refresh path rather than jump to a half-known topic. A dedicated
 * `write_create_topic_success_response.html` capture would harden the parser against
 * a create-only shape divergence, but is not required for the extraction itself.
 *
 * `refreshUrl` is the raw URL HFR returned in the `<meta http-equiv=Refresh>` ;
 * we keep it around for downstream parsing / diagnostics, but it must never be
 * logged in `DiagnosticsLog` (contains the pseudo + topic id in plain text).
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
