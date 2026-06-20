package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.write.PrivateMessageWriteRepository
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.parser.messages.PrivateMessageReplyLinkParser
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody

/**
 * Default [PrivateMessageWriteRepository] implementation (#301, #612). Replies to an HFR private
 * conversation reuse the topic-reply machinery:
 *
 * 1. GET the conversation page (`forum2.php?cat=prive&post={threadId}&page={page}`) and read the
 *    REAL « Ajouter une réponse » link it embeds — a `message.php?cat=prive…` href whose
 *    `numrep` / `ref` / `page` HFR has already filled in (we never invent them). Follow that link
 *    to the dedicated `message.php` reply form and parse it with the shared [ReplyFormParser] to
 *    grab a fresh `hash_check` + every hidden field.
 * 2. POST `bddpost.php` (the same endpoint a topic reply uses) and classify the response with the
 *    shared [ReplySubmitResponseParser].
 *
 * Why message.php and not the embedded quick-reply form (#612) : the conversation page also embeds
 * a `bddpost.php` *quick-reply* form, but HFR serves the owner-only `newdest` field (the full member
 * list of a DT / MultiMP) **only** on the standalone `message.php` form. Sourcing from message.php
 * is therefore what makes [ReplyForm.canManageRecipients] / [ReplyForm.manageableRecipients] true
 * for an owner — the prerequisite for #606's member editor and #612's participant roster. When the
 * page exposes no such link (a one-to-one MP, or a shape HFR reshaped), we fall back to parsing the
 * embedded quick-reply form — the pre-#612 behaviour, which never carries `newdest` anyway.
 *
 * The crucial difference from [DefaultReplyRepository] is the POST body: a private conversation
 * carries `cat=prive` (a String), `post={threadId}`, `subcat=0` and a server-prefilled `numrep`
 * (the last post of the current page — **not** a quote reference) inside the form's hidden fields.
 * [buildFormBody] therefore overrides only the three fields HFR validates (`hash_check`,
 * `verifrequet`, `content_form`) plus the opt-in option toggles, and forwards everything else
 * verbatim — it must never re-assert `cat`/`subcat`/`post`/`numrep` from a typed context, or it would
 * turn `cat=prive` into a number and blank the conversation's `numrep`.
 */
@Singleton
class DefaultPrivateMessageWriteRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val replyFormParser: ReplyFormParser,
    private val replyLinkParser: PrivateMessageReplyLinkParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PrivateMessageWriteRepository {

    override suspend fun fetchReplyForm(context: PrivateMessageReplyContext): ReplyForm {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET MP reply form post=${context.threadId} page=${context.page}",
        )
        return try {
            withContext(ioDispatcher) {
                // #612 — GET the conversation page, then follow its real « Ajouter une réponse »
                // link to the dedicated message.php form (the only one carrying the owner-only
                // `newdest`). When the page exposes no such link, fall back to the embedded
                // quick-reply form (pre-#612 behaviour). There is no anonymous variant — a
                // session-expired GET surfaces SessionExpiredException via the authenticated client.
                val threadHtml = hfrClient.getPrivateMessageThreadPage(
                    threadId = context.threadId,
                    page = context.page,
                )
                val html = fetchReplyFormHtml(threadHtml)
                replyFormParser.parse(html).fold(
                    onSuccess = { form ->
                        // #612 — `canManageRecipients` (owner-only `newdest` presence) is a boolean
                        // derived from the field's existence, NOT its value: safe to log, never
                        // leaks a pseudo (#316). The member CSV itself is never recorded.
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "MP reply form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous} ownerManaged=${form.canManageRecipients}",
                        )
                        form
                    },
                    onFailure = { error ->
                        // No raw HTML excerpt is logged here, unlike DefaultReplyRepository: a private
                        // conversation page can embed the correspondent's pseudo / the private URL
                        // (#316), so we only record the failure class — never a body snapshot.
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "MP reply form parse FAILED: ${error.message ?: error::class.simpleName}",
                        )
                        throw error
                    },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "GET MP reply form SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET MP reply form FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    /**
     * #612 — resolves the HTML the reply form is parsed from. Reads the real « Ajouter une réponse »
     * link off the conversation page ([threadHtml]) and follows it to the dedicated `message.php`
     * form (the only one carrying the owner-only `newdest`). Falls back to the conversation page
     * itself — its embedded `bddpost.php` quick-reply form is what [ReplyFormParser] parsed before
     * #612 — when no link is present (a one-to-one MP, or a shape HFR reshaped) or the follow GET
     * fails. The fallback never carries `newdest`, so an owner whose link disappeared simply loses
     * the member editor / roster until a refetch, never a broken reply.
     *
     * The follow GET's `host` / path / `cat=prive` guard lives in
     * [HfrClient.getPrivateMessageReplyPage]; a [SessionExpiredException] is rethrown (the session
     * really is gone — do not paper over it with the stale thread page), every other failure
     * degrades to the embedded form.
     */
    private suspend fun fetchReplyFormHtml(threadHtml: String): String {
        val replyLink = replyLinkParser.parse(threadHtml) ?: return threadHtml
        return try {
            hfrClient.getPrivateMessageReplyPage(replyLink)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (expired: SessionExpiredException) {
            throw expired
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // The link was malformed or the follow GET failed — fall back to the embedded
            // quick-reply form so a reply is still possible. No raw message logged (#316).
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "MP message.php reply form fetch failed, using embedded form: ${error::class.simpleName}",
            )
            threadHtml
        }
    }

    override suspend fun submitReply(
        context: PrivateMessageReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
        recipientsOverride: String?,
    ): ReplySubmitResult {
        guardAgainstInvalidSubmission(form, bbcodeContent)?.let { early ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "MP POST short-circuited: ${early.reason::class.simpleName}",
            )
            return early
        }

        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "POST MP reply post=${context.threadId} page=${context.page} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildFormBody(form, bbcodeContent, options, recipientsOverride)
        return try {
            withContext(ioDispatcher) {
                // Same endpoint as a topic reply: bddpost.php?config=hfr.inc. All MP routing
                // (cat=prive, post=threadId, numrep, subcat) travels in the form body.
                val responseHtml = hfrClient.submitReply(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "POST MP reply Success")
                    is ReplySubmitResult.Failure ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "POST MP reply Failure reason=${outcome.reason::class.simpleName}",
                        )
                }
                outcome
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "POST MP reply SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST MP reply FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    override suspend fun fetchComposeForm(prefilledRecipient: String?): ReplyForm {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            // Never the recipient pseudo itself — presence flag only, same redaction stance as
            // the rest of this repository (#316).
            "GET MP compose form prefilled=${!prefilledRecipient.isNullOrBlank()}",
        )
        return try {
            withContext(ioDispatcher) {
                val html = hfrClient.getPrivateMessageComposePage(prefilledDest = prefilledRecipient)
                replyFormParser.parse(html).fold(
                    onSuccess = { form ->
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "MP compose form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous}",
                        )
                        form
                    },
                    onFailure = { error ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "MP compose form parse FAILED: ${error.message ?: error::class.simpleName}",
                        )
                        throw error
                    },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "GET MP compose form SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET MP compose form FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    override suspend fun submitNewMessage(
        form: ReplyForm,
        recipients: String,
        subject: String,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        val early = guardAgainstInvalidSubmission(form, bbcodeContent)
            // HFR's own server-side rule (« Vous devez remplir tous les champs ») covers blank
            // dest / sujet too — short-circuit to the same reason without a wasted POST.
            ?: if (recipients.isBlank() || subject.isBlank()) {
                ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
            } else {
                null
            }
        if (early != null) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "MP compose POST short-circuited: ${early.reason::class.simpleName}",
            )
            return early
        }

        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            // Recipient count only — pseudos and subject are private routing data (#316).
            "POST MP compose recipients=${recipients.split(',').count { it.isNotBlank() }} " +
                "subject.length=${subject.length} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildComposeFormBody(
            form = form,
            recipients = recipients,
            subject = subject,
            bbcodeContent = bbcodeContent,
            options = options,
        )
        return try {
            withContext(ioDispatcher) {
                // Same bddpost.php endpoint as every other write : the composer's routing
                // (cat=prive, empty post/numrep, dest, sujet) travels in the form body.
                val responseHtml = hfrClient.submitReply(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "POST MP compose Success")
                    is ReplySubmitResult.Failure ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "POST MP compose Failure reason=${outcome.reason::class.simpleName}",
                        )
                }
                outcome
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(DiagnosticsLog.Level.WARN, LOG_TAG, "POST MP compose SessionExpired")
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST MP compose FAILED: ${error::class.simpleName}",
            )
            throw error
        }
    }

    private fun guardAgainstInvalidSubmission(
        form: ReplyForm,
        bbcodeContent: String,
    ): ReplySubmitResult.Failure? = when {
        form.isAnonymous -> ReplySubmitResult.Failure(ReplyFailureReason.LoginRequired)
        form.hashCheck.isBlank() -> ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        bbcodeContent.isBlank() -> ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        else -> null
    }

    /**
     * Assembles the private-message POST payload. Only [hash_check][HfrConstants], `verifrequet` and
     * `content_form` are overridden, plus the three opt-in option fields (`signature` / `smiley` /
     * `emaill`). **Everything else is forwarded verbatim from [ReplyForm.hiddenFields]** — chiefly
     * `cat=prive`, `post` (=threadId), `numrep` (the conversation's last-post id HFR prefilled, kept
     * as-is — it is NOT a quote reference), `subcat=0`, `page`, `sujet`, `pseudo`. `password` is never
     * relayed. This deliberately does not reuse [DefaultReplyRepository.buildFormBody], whose typed
     * `cat`/`numrep` overrides would corrupt the private-message contract.
     *
     * #606 — [recipientsOverride] mutates the DT/MultiMP member list **only when the form already
     * carries a `newdest` key** (HFR serves it to the conversation's owner alone). The override
     * value replaces HFR's prefilled `newdest` and the key is marked emitted up front, so the
     * verbatim loop never appends a second `newdest`. Without that guard — a simple participant,
     * a one-to-one MP, a topic reply — no `newdest` exists, the override is ignored, and any
     * `newdest` HFR did send is forwarded verbatim (members unchanged).
     */
    private fun buildFormBody(
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
        recipientsOverride: String?,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        builder.add("hash_check", form.hashCheck)
        builder.add("verifrequet", HfrConstants.VERIF_REQUET)
        // #114 — strip non-BMP code points (emojis) HFR would silently truncate the message at.
        builder.add("content_form", sanitizeContentForm(bbcodeContent))
        // Browser-style opt-in: a field is only present when the user enabled it (`emaill` keeps
        // HFR's own two-`l` spelling). The matching keys are marked emitted so the verbatim
        // forwarder below cannot resurrect a stale `value="1"` default from the parsed checkbox.
        if (options.signatureEnabled) builder.add("signature", "1")
        if (options.smileyDisabled) builder.add("smiley", "1")
        if (options.emailNotificationEnabled) builder.add("emaill", "1")
        val emitted = mutableSetOf("hash_check", "verifrequet", "content_form", "signature", "smiley", "emaill")
        // #606 — owner-only member edit. The `newdest` key presence is the owner guard ; only then
        // does a non-null override win over the prefill. Mark it emitted so the verbatim loop below
        // can never duplicate it.
        if (recipientsOverride != null && form.hiddenFields.containsKey("newdest")) {
            builder.add("newdest", recipientsOverride)
            emitted += "newdest"
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Belt-and-braces: ReplyFormParser already drops `password`, never relay it.
            if (key == "password") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    /**
     * Assembles the new-conversation POST payload. Same verbatim-forward philosophy as
     * [buildFormBody], with two more user-typed overrides : `dest` (recipients, comma-separated)
     * and `sujet` — both rendered by HFR as *text* inputs on the composer, so the parser collects
     * their (empty / prefilled) values into [ReplyForm.hiddenFields] and we must mark them emitted
     * before the verbatim loop, or a stale prefill would shadow what the user typed. Everything
     * else (`cat=prive`, empty `post`/`numrep`/`numreponse`, `MsgIcon`, `pseudo`, `parents`,
     * `stickold`, `ColorUsedMem`, `wysiwyg`, …) is forwarded untouched — the composer's hidden
     * contract was captured live (fixture `mp_compose_form.html`) but HFR can reshape it freely.
     */
    @Suppress("LongParameterList") // One parameter per user-typed wire field.
    private fun buildComposeFormBody(
        form: ReplyForm,
        recipients: String,
        subject: String,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        builder.add("hash_check", form.hashCheck)
        builder.add("verifrequet", HfrConstants.VERIF_REQUET)
        // #114 — strip non-BMP code points (emojis) HFR would silently truncate the message at.
        builder.add("content_form", sanitizeContentForm(bbcodeContent))
        builder.add("dest", recipients)
        builder.add("sujet", subject)
        if (options.signatureEnabled) builder.add("signature", "1")
        if (options.smileyDisabled) builder.add("smiley", "1")
        if (options.emailNotificationEnabled) builder.add("emaill", "1")
        val emitted = mutableSetOf(
            "hash_check", "verifrequet", "content_form", "dest", "sujet",
            "signature", "smiley", "emaill",
        )
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            if (key == "password") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    private companion object {
        private const val LOG_TAG = "MpWriteRepository"
    }
}
