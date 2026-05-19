package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody

/**
 * Default [EditPostRepository] implementation. Mirrors [DefaultReplyRepository]
 * but targets the edit-post wire shape :
 *
 * - GET `message.php?…&numreponse={N}` — same path as the reply form, plus the
 *   `numreponse` parameter that switches HFR into « edit existing post » mode
 *   (action target becomes `bdd.php`, `<textarea name=content_form>` is
 *   prefilled with the post's current BBCode).
 * - POST `bdd.php?config=hfr.inc` — distinct from `bddpost.php` ; HFR routes
 *   the two flows through different endpoints on purpose.
 *
 * Wire differences from reply :
 * - `numreponse` carries the post being edited (reply : empty).
 * - `numrep` stays empty (reply also empty, quote fills it).
 * - The form HTML carries a `<input name="delete" value="1">` checkbox that we
 *   **never** transmit — deletion is destructive and out of scope for #147.
 *
 * Everything else (`hash_check`, `verifrequet=1100`, `cat`/`subcat`/`post`/
 * `page`/`sujet`, per-post options, anti-flood / locked-topic / invalid-token
 * classifier) is shared with the reply repository.
 */
@Singleton
class DefaultEditPostRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val replyFormParser: ReplyFormParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EditPostRepository {

    override suspend fun fetchEditPostForm(context: EditPostContext): ReplyForm {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET edit form cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page} numreponse=${context.numreponse}",
        )
        return try {
            withContext(ioDispatcher) {
                val html = hfrClient.getEditPostForm(
                    cat = context.cat,
                    subcat = context.subcat,
                    post = context.topicId,
                    page = context.page,
                    numreponse = context.numreponse,
                )
                replyFormParser.parse(html).fold(
                    onSuccess = { form ->
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "edit form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous} sujet=\"${form.sujet}\" " +
                                "initialContent.length=${form.initialContent.length}",
                        )
                        form
                    },
                    onFailure = { error ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "edit form parse FAILED: ${error.message ?: error::class.simpleName}",
                        )
                        throw error
                    },
                )
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET edit form SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET edit form FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
    }

    override suspend fun submitEditPost(
        context: EditPostContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        guardAgainstInvalidSubmission(form, bbcodeContent)?.let { early ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST short-circuited: ${early.reason::class.simpleName}",
            )
            return early
        }

        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "POST edit cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page} " +
                "numreponse=${context.numreponse} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildEditFormBody(context, form, bbcodeContent, options)
        return try {
            withContext(ioDispatcher) {
                val responseHtml = hfrClient.submitEditPost(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "POST edit Success refreshUrl=${outcome.refreshUrl} " +
                                "targetPage=${outcome.targetPage}",
                        )
                    is ReplySubmitResult.Failure ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "POST edit Failure reason=${outcome.reason::class.simpleName}",
                        )
                }
                outcome
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST edit SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST edit FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
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

    @Suppress("LongMethod") // HFR write contract = one declarative POST body, splitting hurts readability.
    private fun buildEditFormBody(
        context: EditPostContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val overrides = buildMap {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            put("content_form", bbcodeContent)
            // Edit : numreponse identifies the post being edited. numrep stays
            // empty (only quote uses it, and quote always goes through the
            // reply repository).
            put("numreponse", context.numreponse.toString())
            put("numrep", "")
            put("cat", context.cat.toString())
            put("subcat", context.subcat.toString())
            put("post", context.topicId.toString())
            put("page", context.page.toString())
            put("sujet", form.sujet)
            // Route the typed icon as the POST source-of-truth — same defense-
            // in-depth as the reply repository.
            form.msgIcon?.let { put("MsgIcon", it) }
        }
        val emitted = mutableSetOf<String>()
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        // Per-post options : browser-style submit, field is only present when ON.
        if (options.signatureEnabled) builder.add("signature", "1")
        if (options.smileyDisabled) builder.add("smiley", "1")
        if (options.emailNotificationEnabled) builder.add("emaill", "1")
        emitted += setOf("signature", "smiley", "emaill")
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Hard deny : `password` (parser-side filter already protects, this
            // is belt-and-braces) and `delete` (the edit form ships a delete
            // checkbox that we never want to send for the edit MVP — deletion
            // is destructive and out of scope #147).
            if (key == "password" || key == "delete") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    private companion object {
        private const val LOG_TAG = "EditPostRepository"
    }
}
