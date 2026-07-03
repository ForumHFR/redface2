package fr.forumhfr.redface2.core.model.write

/**
 * Vague 4 (#604) lot 2 — a post retained for citation, as the QUOTE CARDS see it: identity plus a
 * one-line plain-text excerpt, both captured AT SELECTION TIME (cadrage Codex — the UI never
 * re-parses a post to render a card ; the exact `[quotemsg]` materialisation is a separate,
 * server-fed step at submit/escalation time and only needs [numreponse]).
 *
 * Deliberately UI-agnostic (a « card » is presentation) and NOT persisted: quote selections are
 * transient by design, like the multi-quote basket they extend — process death means re-selecting.
 *
 * The [excerpt] is a snapshot of the post as it was when selected ; the post may be edited on HFR
 * before the reply is submitted. Accepted trade-off: the materialised quote is fetched fresh, the
 * card is only a reminder of what was picked.
 */
data class QuotedPostPreview(
    val numreponse: Int,
    val author: String,
    val excerpt: String,
)
