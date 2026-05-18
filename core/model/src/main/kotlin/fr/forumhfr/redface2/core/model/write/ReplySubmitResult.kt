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
     * wants the client to land on (typically `…/sujet_X_PAGE.htm#bas`). Callers
     * should treat it as opaque and just navigate there, optionally extracting
     * [targetPage] from the URL when they need to refresh a known cache.
     */
    data class Success(
        val refreshUrl: String?,
        val targetPage: Int?,
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
