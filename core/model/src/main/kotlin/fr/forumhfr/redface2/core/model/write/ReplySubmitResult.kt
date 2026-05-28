package fr.forumhfr.redface2.core.model.write

/**
 * Outcome of a POST to `bddpost.php`. Every variant is derived from a real HFR
 * response (cf. fixtures `core/parser/src/test/resources/fixtures/write_*_response.html`
 * and friends) — no inferred error states.
 */
sealed interface ReplySubmitResult {

    /**
     * HFR replied with the success page. [refreshUrl] is the value carried by the
     * `<meta http-equiv="Refresh" content="N; url=…">` header — exactly the URL HFR
     * wants the client to land on (typically `…/sujet_X_PAGE.htm#bas` for a fresh
     * reply, `…/sujet_X_PAGE.htm#t{numreponse}` for quote / edit / edit-FP). Callers
     * should treat it as opaque and just navigate there, optionally extracting
     * [targetPage] / [numreponse] from the URL when they need to refresh a known
     * cache or scroll to the new post.
     *
     * [numreponse] is non-null when HFR's refresh URL exposes a `#t{N}` fragment
     * (quote, edit post, edit FP, and any future endpoint that anchors on the
     * created/edited post). For a plain reply HFR anchors `#bas` instead, so
     * [numreponse] is null and the screen falls back to scrolling to the end of
     * the refreshed page — issue #200.
     *
     * [topicId] is the first integer of the `sujet_{topicId}_{page}` segment of
     * the refresh URL. For reply / quote / edit the caller already knows the topic
     * it posted to, so it's informational ; it matters for **create-topic** (#206),
     * where the freshly-allocated topic id is only learnable from this URL — the
     * `bddpost.php` success shape is identical across all four flows, so the same
     * extraction serves them all.
     */
    data class Success(
        val refreshUrl: String?,
        val targetPage: Int?,
        val numreponse: Int? = null,
        val topicId: Int? = null,
    ) : ReplySubmitResult

    /** HFR refused to post and surfaced one of the known reasons. */
    data class Failure(val reason: ReplyFailureReason) : ReplySubmitResult
}

/**
 * Classified failure reasons. Each variant maps to a literal HFR error string we
 * have observed in the Phase 2A captures. [Unknown] is the safety net when HFR
 * returns a page we don't recognise — the UI surfaces a generic error and keeps
 * the user's draft intact.
 */
sealed interface ReplyFailureReason {
    /** « Vous devez remplir tous les champs avant de poster ce message » */
    data object EmptyMessage : ReplyFailureReason

    /** « Une erreur est survenue lors de l'envoi des données » */
    data object InvalidHashCheck : ReplyFailureReason

    /** « vous ne pouvez poster plus de 3 réponses consécutives… » */
    data object AntiFlood : ReplyFailureReason

    /** « Désolé ce sujet a été fermé » */
    data object TopicLocked : ReplyFailureReason

    /**
     * HFR served the anonymous `pseudo`/`password` composer, which Redface 2 refuses
     * to use. Surface a "login required" CTA in the UI.
     */
    data object LoginRequired : ReplyFailureReason

    /**
     * Unrecognised response. The UI surfaces a generic "unexpected response" message
     * and keeps the user's draft intact. No raw text is carried — the response body
     * is intentionally dropped to avoid leaking `hash_check` or session metadata
     * into a snapshot or a log. If we ever need to ship diagnostics, add a dedicated,
     * pre-redacted field rather than storing the full body here.
     */
    data object Unknown : ReplyFailureReason
}
