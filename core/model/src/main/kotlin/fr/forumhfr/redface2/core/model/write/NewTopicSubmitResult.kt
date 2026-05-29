package fr.forumhfr.redface2.core.model.write

/**
 * Outcome of a `POST bddpost.php` for a brand-new topic (Phase 2E #149).
 *
 * Distinct from `ReplySubmitResult` because the navigation outcome differs :
 * reply/edit land back on the topic, whereas create-topic lands on the category
 * listing (see below). Kept as its own type so the create path never carries
 * reply-only scroll fields.
 *
 * `newTopicId` / `newNumreponse` are **always null in practice** : verified live
 * (`write_create_topic_success_response.html`, #214), a successful create makes HFR
 * refresh to the category LISTING (`…/liste_sujet-1.htm`) — it never exposes the
 * freshly-allocated topic id. The fields are kept nullable (rather than removed) so
 * the navigation contract stays explicit, but the original #206 goal of navigating
 * straight to the created topic is not achievable from HFR's response ; the host
 * lands on `CategoryRoute` (the listing, where the new topic shows on top).
 *
 * `refreshUrl` is the raw URL HFR returned in the `<meta http-equiv=Refresh>` (the
 * listing URL on a create) ; kept for diagnostics but never logged in plain text.
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
