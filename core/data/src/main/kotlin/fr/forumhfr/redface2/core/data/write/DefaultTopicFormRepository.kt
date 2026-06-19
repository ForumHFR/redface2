package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.write.TopicFormRepository
import fr.forumhfr.redface2.core.model.write.EditFirstPostContext
import fr.forumhfr.redface2.core.model.write.NewTopicContext
import fr.forumhfr.redface2.core.model.write.NewTopicSubmitResult
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import fr.forumhfr.redface2.core.parser.write.TopicFormParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody

/**
 * Default [TopicFormRepository] implementation for Phase 2D #148 (edit first
 * post). Wire endpoints are the same as a regular post edit (`message.php`
 * GET, `bdd.php` POST) and the response classifier is shared with
 * [DefaultEditPostRepository] / [DefaultReplyRepository] ; the topic-level
 * shape only diverges on the form contract (`sujet`, `subcat`, poll).
 *
 * Diagnostics never carry `hash_check`, the BBCode content, the raw
 * `numreponse` of the edited post, or HFR's refresh URL (which anchors
 * `#t{numreponse}`). The success log collapses the URL to a presence boolean.
 */
@Singleton
class DefaultTopicFormRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val topicFormParser: TopicFormParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TopicFormRepository {

    override suspend fun fetchEditFirstPostForm(context: EditFirstPostContext): TopicForm {
        // `numreponse` identifies the user's own first post ; kept out of the
        // INFO line, same rule as the post-level edit repository.
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET FP edit form cat=${context.cat} subcat=${context.subcat} " +
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
                topicFormParser.parseEditFirstPost(html).fold(
                    onSuccess = { form ->
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "FP form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous} " +
                                "selectedSubcat=${form.selectedSubcat} " +
                                "subcategoryChoices=${form.subcategoryChoices.size} " +
                                "pollPresent=${form.poll.present}",
                        )
                        form
                    },
                    onFailure = { error ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "FP form parse FAILED: ${error.message ?: error::class.simpleName}",
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
                "GET FP edit form SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET FP edit form FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
    }

    override suspend fun submitEditFirstPost(
        context: EditFirstPostContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions,
    ): ReplySubmitResult {
        guardAgainstInvalidSubmission(form, subject, bbcodeContent, selectedSubcat)?.let { early ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "FP POST short-circuited: ${early.reason::class.simpleName}",
            )
            return early
        }

        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "POST FP edit cat=${context.cat} subcat=$selectedSubcat " +
                "post=${context.topicId} page=${context.page} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildEditFirstPostBody(context, form, subject, bbcodeContent, selectedSubcat, options)
        return try {
            withContext(ioDispatcher) {
                val responseHtml = hfrClient.submitEditPost(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        // HFR anchors the FP edit success refresh on
                        // `#t{numreponse}` ; logging it would leak the FP
                        // numreponse into the diagnostics buffer. Collapse to
                        // a presence boolean + the parsed page (safe).
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "POST FP edit Success hasRefreshUrl=${outcome.refreshUrl != null} " +
                                "targetPage=${outcome.targetPage}",
                        )
                    is ReplySubmitResult.Failure ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "POST FP edit Failure reason=${outcome.reason::class.simpleName}",
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
                "POST FP edit SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST FP edit FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
    }

    private fun guardAgainstInvalidSubmission(
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
    ): ReplySubmitResult.Failure? = when {
        form.isAnonymous -> ReplySubmitResult.Failure(ReplyFailureReason.LoginRequired)
        form.hashCheck.isBlank() -> ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        bbcodeContent.isBlank() -> ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        subject.isBlank() -> ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)
        // #213 — a category WITHOUT a sub-category (HFR served no <select name=subcat>,
        // `hasSubcategorySelect = false`, e.g. cat IA) posts a legitimate `subcat=0`.
        // A category WITH sub-categories must still carry a real `subcat > 0` : posting
        // 0 there would silently drop the topic into « no sub-category ». Edit FP always
        // has `hasSubcategorySelect = true` (the parser fail-fasts otherwise), so this
        // keeps the strict `subcat > 0` requirement on the edit path.
        selectedSubcat < 0 -> ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        selectedSubcat == 0 && form.hasSubcategorySelect -> ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        else -> null
    }

    @Suppress("LongMethod", "LongParameterList") // Declarative POST body ; one place to read the whole contract.
    private fun buildEditFirstPostBody(
        context: EditFirstPostContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val overrides = buildMap {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            // #114 — strip non-BMP code points (emojis) HFR would silently truncate the post at.
            put("content_form", sanitizeContentForm(bbcodeContent))
            put("sujet", subject)
            put("numreponse", context.numreponse.toString())
            put("numrep", "")
            put("cat", context.cat.toString())
            // Allow the user to re-categorise (FP form ships a writable <select>),
            // overriding the original context.subcat with whatever the UI picked.
            put("subcat", selectedSubcat.toString())
            put("post", context.topicId.toString())
            put("page", context.page.toString())
            form.msgIcon?.let { put("MsgIcon", it) }
        }
        val emitted = mutableSetOf<String>()
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        // Per-post options : browser-style submit (key absent when toggle off).
        if (options.signatureEnabled) builder.add("signature", "1")
        if (options.smileyDisabled) builder.add("smiley", "1")
        if (options.emailNotificationEnabled) builder.add("emaill", "1")
        emitted += setOf("signature", "smiley", "emaill")
        // Poll fields are preserved verbatim (read-only in this version) ; the
        // map already only contains values HFR would have sent on submit.
        form.poll.fields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            builder.add(key, value)
            emitted += key
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Belt-and-braces : the parser already strips `password` / `delete`,
            // we keep the deny rules here so a future refactor cannot leak the
            // destructive « Effacer l'intégralité du sujet » checkbox.
            if (key == "password" || key == "delete") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    override suspend fun fetchNewTopicForm(context: NewTopicContext): TopicForm {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET new-topic form cat=${context.cat} entrySubcat=${context.entrySubcat ?: "(none)"}",
        )
        return try {
            withContext(ioDispatcher) {
                val html = hfrClient.getNewTopicForm(cat = context.cat, entrySubcat = context.entrySubcat)
                topicFormParser.parseNewTopic(html).fold(
                    onSuccess = { form ->
                        diagnostics.record(
                            DiagnosticsLog.Level.DEBUG,
                            LOG_TAG,
                            "new-topic form parsed: hiddenFields=${form.hiddenFields.size} " +
                                "anonymous=${form.isAnonymous} " +
                                "subcategoryChoices=${form.subcategoryChoices.size} " +
                                "preSelectedSubcat=${form.selectedSubcat ?: "(none)"} " +
                                "pollPresent=${form.poll.present}",
                        )
                        form
                    },
                    onFailure = { error ->
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "new-topic form parse FAILED: ${error.message ?: error::class.simpleName}",
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
                "GET new-topic form SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET new-topic form FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
    }

    override suspend fun submitNewTopic(
        context: NewTopicContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions,
    ): NewTopicSubmitResult {
        guardAgainstInvalidSubmission(form, subject, bbcodeContent, selectedSubcat)?.let { early ->
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "new-topic POST short-circuited: ${early.reason::class.simpleName}",
            )
            return NewTopicSubmitResult.Failure(early.reason)
        }

        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "POST new-topic cat=${context.cat} subcat=$selectedSubcat " +
                "entrySubcat=${context.entrySubcat ?: "(none)"} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildNewTopicBody(context, form, subject, bbcodeContent, selectedSubcat, options)
        return try {
            withContext(ioDispatcher) {
                val responseHtml = hfrClient.submitNewTopic(formBody)
                // The wire endpoint is shared with reply/quote ; the same classifier
                // disambiguates Success vs the failure variants (#214 added the
                // create-specific success marker « Votre message a été posté avec succès »).
                // IMPORTANT (verified live, cf. write_create_topic_success_response.html) :
                // on a successful create HFR refreshes to the category LISTING, not to the
                // new topic, and returns NO topic id. So `outcome.topicId`/`numreponse` are
                // always null here and the navigation host lands on the category listing —
                // direct navigation to the created topic (the original #206 goal) is not
                // possible because HFR never exposes the freshly-allocated id.
                when (val outcome = replySubmitResponseParser.parse(responseHtml)) {
                    is ReplySubmitResult.Success -> {
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "POST new-topic Success hasRefreshUrl=${outcome.refreshUrl != null} " +
                                "hasTopicId=${outcome.topicId != null} " +
                                "targetCat=${context.cat} targetSubcat=$selectedSubcat",
                        )
                        NewTopicSubmitResult.Success(
                            newTopicId = outcome.topicId,
                            newNumreponse = outcome.numreponse,
                            targetCat = context.cat,
                            targetSubcat = selectedSubcat,
                            refreshUrl = outcome.refreshUrl,
                        )
                    }
                    is ReplySubmitResult.Failure -> {
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "POST new-topic Failure reason=${outcome.reason::class.simpleName}",
                        )
                        NewTopicSubmitResult.Failure(outcome.reason)
                    }
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST new-topic SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST new-topic FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
    }

    @Suppress("LongMethod", "LongParameterList") // Declarative POST body ; one place to read the whole contract.
    private fun buildNewTopicBody(
        context: NewTopicContext,
        form: TopicForm,
        subject: String,
        bbcodeContent: String,
        selectedSubcat: Int,
        options: ReplyFormOptions,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val overrides = buildMap {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            // #114 — strip non-BMP code points (emojis) HFR would silently truncate the post at.
            put("content_form", sanitizeContentForm(bbcodeContent))
            put("sujet", subject)
            put("cat", context.cat.toString())
            // User's dropdown choice — always > 0 thanks to guardAgainstInvalidSubmission.
            put("subcat", selectedSubcat.toString())
            // `from_subcat` is the d'arrivée chip the composer was opened from
            // (the URL `subcat=` parameter at GET time), not the dropdown
            // choice. Prefer the value HFR echoed back in the form ; fall back
            // to the context's entrySubcat ; the empty string is acceptable
            // when neither is present (« Toutes » view).
            val fromSubcat = form.hiddenFields["from_subcat"]
                ?: context.entrySubcat?.toString()
                ?: ""
            put("from_subcat", fromSubcat)
            // Create-topic has no existing post : these three fields are always
            // empty on the wire (HFR routes the form to the create path purely
            // by the absence of `post`/`numreponse`).
            put("post", "")
            put("numreponse", "")
            put("numrep", "")
            put("page", "1")
            form.msgIcon?.let { put("MsgIcon", it) }
        }
        val emitted = mutableSetOf<String>()
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        // Per-post options : browser-style submit (key absent when toggle off).
        if (options.signatureEnabled) builder.add("signature", "1")
        if (options.smileyDisabled) builder.add("smiley", "1")
        if (options.emailNotificationEnabled) builder.add("emaill", "1")
        emitted += setOf("signature", "smiley", "emaill")
        // Poll fields are owned by TopicPollForm.fields. The create flow has
        // no active poll in the Phase 2A capture, so this branch is dormant ;
        // it is symmetric with Edit FP to keep the contract single-source.
        form.poll.fields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            builder.add(key, value)
            emitted += key
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Belt-and-braces : the parser already strips `password` / `delete`.
            // We keep the deny rules here so a future refactor cannot leak the
            // destructive checkbox or an inherited password.
            if (key == "password" || key == "delete") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    private companion object {
        private const val LOG_TAG = "TopicFormRepository"
    }
}
