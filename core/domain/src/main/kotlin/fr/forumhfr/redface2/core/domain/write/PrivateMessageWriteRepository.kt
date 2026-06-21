package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult

/**
 * Repository surface for replying to an HFR private-message conversation (#301).
 *
 * The wire shape is the same family as a topic reply — HFR embeds a `bddpost.php` form in the
 * conversation page (`forum2.php?cat=prive&post={threadId}`) and the POST goes to the same
 * `bddpost.php` endpoint — so this reuses the generic [ReplyForm] / [ReplySubmitResult] models and
 * the shared form / response parsers. It is kept as a **separate** interface from [ReplyRepository]
 * because a private conversation has no numeric `cat` / `subcat` / quote semantics: the routing
 * lives entirely in the form's hidden fields (`cat=prive`, `post`, `numrep`, `subcat=0`, …), which
 * the implementation forwards verbatim rather than re-asserting from a typed context.
 *
 * - [fetchReplyForm] GETs the conversation page and parses the embedded `bddpost.php` form (fresh
 *   `hash_check`). It returns a [ReplyForm] even when HFR served the anonymous composer (caller
 *   inspects [ReplyForm.isAnonymous]); transport / session-expiry failures are raised.
 * - [submitReply] POSTs the reply and classifies the response into a [ReplySubmitResult]. The
 *   private-message POST success sentence is not pinned by a live fixture (a real send to a third
 *   party was intentionally avoided), so callers must treat an unrecognised
 *   ([fr.forumhfr.redface2.core.model.write.ReplyFailureReason.Unknown]) response as non-destructive
 *   — keep the draft and let the user verify the conversation — rather than asserting a hard failure.
 *
 * Composing a **new** conversation (#301 follow-up) rides the same family : HFR's standalone MP
 * composer (`message.php?cat=prive` without `post=`) posts to the same `bddpost.php` endpoint with
 * `post`/`numrep` empty and two user-typed routing fields — `dest` (recipient pseudos,
 * comma-separated for a MultiMP) and `sujet` (free subject, HFR caps it at 70 chars). Contract
 * captured live 2026-06-11 (fixture `mp_compose_form.html`) ; the POST response was deliberately
 * never exercised, so the same non-destructive-Unknown rule applies to [submitNewMessage].
 */
interface PrivateMessageWriteRepository {

    /**
     * #612 — fetches the MP reply form. By default it follows the conversation's real « Ajouter une
     * réponse » link to the dedicated `message.php` form (the only one carrying the owner-only
     * `newdest`), falling back to the embedded `forum2.php` quick-reply when the follow GET fails so a
     * reply stays possible. [allowEmbeddedFallback] = `false` DISABLES that fallback: the roster path
     * needs `newdest`, so a failed `message.php` GET must surface as an error (retry), not silently
     * degrade to a quick-reply that lacks `newdest` and reads as « no roster ».
     */
    suspend fun fetchReplyForm(
        context: PrivateMessageReplyContext,
        allowEmbeddedFallback: Boolean = true,
    ): ReplyForm

    /**
     * POSTs a reply to the conversation. [recipientsOverride] (#606) is honoured **only when the
     * form is an owner's DT/MultiMP** — i.e. [ReplyForm.canManageRecipients] is true (HFR served
     * the `newdest` field). When non-null on such a form, the new CSV replaces HFR's prefilled
     * `newdest`, adding / removing members alongside the posted reply. On any other form (a simple
     * participant, a one-to-one MP, a topic reply) the override is ignored and `newdest`, if any,
     * is forwarded verbatim — the safe default that never mutates the member list.
     */
    suspend fun submitReply(
        context: PrivateMessageReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
        recipientsOverride: String? = null,
    ): ReplySubmitResult

    /**
     * GETs the standalone MP composer and parses its `bddpost.php` form. [prefilledRecipient]
     * pre-fills HFR's `dest` field server-side (future « send a MP to this user » entry points) ;
     * the parsed prefill comes back inside [ReplyForm.hiddenFields]'s `dest` entry.
     */
    suspend fun fetchComposeForm(prefilledRecipient: String? = null): ReplyForm

    /**
     * POSTs a new private conversation. [recipients] lands in HFR's `dest` field verbatim —
     * multiple pseudos are comma-separated (HFR's own field help) ; [subject] in `sujet`.
     * Blank [recipients] / [subject] / [bbcodeContent] short-circuit to an
     * [fr.forumhfr.redface2.core.model.write.ReplyFailureReason.EmptyMessage] failure (HFR's own
     * « remplir tous les champs » rule) without touching the network.
     */
    @Suppress("LongParameterList") // One parameter per user-typed wire field.
    suspend fun submitNewMessage(
        form: ReplyForm,
        recipients: String,
        subject: String,
        bbcodeContent: String,
        options: ReplyFormOptions = ReplyFormOptions(),
    ): ReplySubmitResult
}
