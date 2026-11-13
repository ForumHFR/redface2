package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.domain.write.EditPostRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.PrivateMessageEditContext
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
import kotlinx.coroutines.flow.first
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
 *
 * Private edits (#1625) validate their separate target, preserve DOM field order and server-owned
 * parents, and protect the MPStorage document. Their diagnostics contain no private content or ids.
 */
@Singleton
@Suppress("LongParameterList") // MPStorage protection needs the active owner and its local location cache.
class DefaultEditPostRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val replyFormParser: ReplyFormParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
    private val diagnostics: DiagnosticsLog,
    private val smileyUsageRecorder: SuccessfulSmileyUsageRecorder,
    private val authRepository: AuthRepository,
    private val mpStorageLocationStore: MpStorageLocationStore,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : EditPostRepository {

    override suspend fun fetchEditPostForm(context: EditPostContext): ReplyForm {
        // `numreponse` identifies one of the user's own posts ; we keep it out
        // of the INFO line. HFR's refresh URL on a successful edit also embeds
        // the same id (`#t{numreponse}`), so the success-path log below
        // collapses the URL to a presence boolean + the parsed page rather
        // than dumping the raw value.
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET edit form cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page}",
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
                replyFormParser.parseEditPost(html).fold(
                    onSuccess = { form ->
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "edit form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous} " +
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
                "post=${context.topicId} page=${context.page} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildEditFormBody(context, form, bbcodeContent, options)
        return try {
            withContext(ioDispatcher) {
                val smileyUsageOwner = smileyUsageRecorder.captureOwner()
                val responseHtml = hfrClient.submitEditPost(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                if (outcome is ReplySubmitResult.Success) {
                    smileyUsageRecorder.record(
                        smileyUsageOwner, formBody, options.smileyDisabled, form.initialContent,
                    )
                }
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        // HFR's edit success refresh URL anchors the edited
                        // post (`…sujet_{topic}_{page}.htm#t{numreponse}`).
                        // Logging it verbatim would leak `numreponse` of the
                        // user's own post into the alpha diagnostics panel —
                        // we therefore collapse it to a presence boolean.
                        // `targetPage` is parsed out and safe to expose.
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "POST edit Success hasRefreshUrl=${outcome.refreshUrl != null} " +
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

    override suspend fun fetchEditPostForm(context: PrivateMessageEditContext): ReplyForm = withContext(ioDispatcher) {
        guardPrivateStorageMessage(context)
        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "GET MP edit form")
        val html = hfrClient.getPrivateMessageEditForm(context.threadId, context.numreponse, context.page)
        replyFormParser.parseEditPost(html, context).getOrThrow().also { form ->
            guardPrivateEditForm(context, form)
        }
    }

    /** PENDING(#1625): MP edit success response not yet observed live. */
    override suspend fun submitEditPost(
        context: PrivateMessageEditContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult = withContext(ioDispatcher) {
        guardPrivateStorageMessage(context)
        guardPrivateEditForm(context, form)
        guardAgainstInvalidSubmission(form, bbcodeContent)?.let { return@withContext it }
        val body = buildPrivateEditFormBody(context, form, bbcodeContent, options)
        // No private ids, titles, bodies, refresh URLs or exception messages in diagnostics.
        diagnostics.record(DiagnosticsLog.Level.INFO, LOG_TAG, "POST MP edit")
        val owner = smileyUsageRecorder.captureOwner()
        val parsed = replySubmitResponseParser.parse(hfrClient.submitEditPost(body))
        val outcome = if (parsed is ReplySubmitResult.Success) {
            smileyUsageRecorder.record(owner, body, options.smileyDisabled, form.initialContent)
            // Private refresh URL syntax is unobserved; the edit target already owns these coordinates.
            parsed.copy(targetPage = context.page, numreponse = context.numreponse, topicId = context.threadId)
        } else {
            // Unknown stays Unknown: the durable submission adapter treats it as UNCERTAIN.
            parsed
        }
        diagnostics.record(
            DiagnosticsLog.Level.INFO, LOG_TAG,
            "POST MP edit ${if (outcome is ReplySubmitResult.Success) "Success" else "Failure"}",
        )
        outcome
    }

    /** Never discover storage over the network to decide whether an ordinary message is editable. */
    private suspend fun guardPrivateStorageMessage(context: PrivateMessageEditContext) {
        val owner = (authRepository.observeAuthState().first() as? AuthState.Authenticated)?.pseudo
        val storage = mpStorageLocationStore.read(owner)
        check(storage == null || storage.threadId != context.threadId || storage.numreponse != context.numreponse) {
            "MPStorage document cannot be edited through the message editor"
        }
        // A cold cache cannot identify the storage thread before GET. PR 2 must also hide its edit
        // action from the loaded thread's subject; the form subject guard below protects POST here.
    }

    private fun guardPrivateEditForm(context: PrivateMessageEditContext, form: ReplyForm) {
        check(
            form.hiddenFields["cat"] == "prive" &&
                form.hiddenFields["post"] == context.threadId.toString() &&
                form.hiddenFields["numreponse"] == context.numreponse.toString() &&
                form.hiddenFields["numrep"] == "" &&
                form.submitFieldOrder.containsAll(
                    listOf(
                        "hash_check", "cat", "post", "numreponse", "numrep", "page", "verifrequet", "sujet",
                        "content_form",
                    ),
                ),
        ) { "Private edit form target mismatch or incomplete form" }
        check(
            form.sujet != MpStorageRepository.STORAGE_SUBJECT_HASH &&
                form.hiddenFields["sujet"] != MpStorageRepository.STORAGE_SUBJECT_HASH,
        ) {
            "MPStorage document cannot be edited through the message editor"
        }
        check("dest" !in form.hiddenFields && "newdest" !in form.hiddenFields) {
            "Private edit form cannot change recipients"
        }
    }

    private fun buildPrivateEditFormBody(
        context: PrivateMessageEditContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): FormBody {
        val values = form.hiddenFields.toMutableMap().apply {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            put("content_form", sanitizeContentForm(bbcodeContent))
            put("page", context.page.toString())
            put("sujet", if (form.isSubjectEditable) form.sujet else getValue("sujet"))
            // The parser drops all password values. Only this empty legacy slot may be submitted.
            put("password", "")
            form.msgIcon?.let { put("MsgIcon", it) }
            remove("signature")
            remove("smiley")
            remove("emaill")
            if (options.signatureEnabled) put("signature", "1")
            if (options.smileyDisabled) put("smiley", "1")
            if (options.emailNotificationEnabled) put("emaill", "1")
            remove("delete")
        }
        return FormBody.Builder(Charsets.UTF_8).apply {
            form.submitFieldOrder.distinct().forEach { name ->
                values[name]?.let { add(name, it) }
            }
        }.build()
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
            // #114 — strip non-BMP code points (emojis) HFR would silently truncate the post at.
            put("content_form", sanitizeContentForm(bbcodeContent))
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
