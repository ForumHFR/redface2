package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
import fr.forumhfr.redface2.core.domain.write.DeletePostRepository
import fr.forumhfr.redface2.core.domain.write.DeletePostResult
import fr.forumhfr.redface2.core.model.write.EditPostContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
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
 * Default [DeletePostRepository] (#292). Deletion reuses the **edit form** (`bdd.php`) plus a
 * `delete=1` field — there is no dedicated HFR delete endpoint. The flow is a single
 * fetch-then-submit:
 *
 * 1. GET the edit form for the post (same `message.php?…&numreponse={N}` request as the editor),
 *    which yields a fresh `hash_check` and all the hidden fields HFR expects back.
 * 2. POST `bdd.php?config=hfr.inc` with those fields **plus `delete=1`** (content is irrelevant —
 *    `delete=1` short-circuits HFR's "message must not be empty" check).
 *
 * Because the fetch and the submit are back-to-back, the token can never go stale (unlike the
 * editor, where the user types between load and submit), so no invalid-token refetch loop is needed.
 *
 * The POST response is classified by the shared [ReplySubmitResponseParser] (« Message effacé avec
 * succès ! »). Whether a whole topic was deleted is read from the success refresh URL: a normal-post
 * delete refreshes to the topic (`sujet_{id}_{page}` → `topicId` non-null); a first-post delete
 * removes the whole topic and refreshes to the listing (`liste_sujet` → `topicId` null).
 */
@Singleton
class DefaultDeletePostRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val replyFormParser: ReplyFormParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
    private val diagnostics: DiagnosticsLog,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : DeletePostRepository {

    override suspend fun deletePost(context: EditPostContext): DeletePostResult {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "delete cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page}",
        )
        return try {
            withContext(ioDispatcher) {
                val formHtml = hfrClient.getEditPostForm(
                    cat = context.cat,
                    subcat = context.subcat,
                    post = context.topicId,
                    page = context.page,
                    numreponse = context.numreponse,
                )
                val form = replyFormParser.parse(formHtml).getOrElse { error ->
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG,
                        "delete form parse FAILED: ${error.message ?: error::class.simpleName}",
                    )
                    return@withContext DeletePostResult.Failure(ReplyFailureReason.Unknown)
                }
                if (form.isAnonymous) {
                    // HFR served the anonymous composer — the session is not (or no longer)
                    // authenticated, so the delete would silently no-op. Surface a login CTA.
                    return@withContext DeletePostResult.Failure(ReplyFailureReason.LoginRequired)
                }
                if (form.hashCheck.isBlank()) {
                    return@withContext DeletePostResult.Failure(ReplyFailureReason.InvalidHashCheck)
                }
                val responseHtml = hfrClient.submitEditPost(buildDeleteFormBody(context, form))
                val outcome = replySubmitResponseParser.parse(responseHtml)
                outcome.toDeleteResult().also { result ->
                    diagnostics.record(
                        DiagnosticsLog.Level.INFO,
                        LOG_TAG,
                        when (result) {
                            is DeletePostResult.Success ->
                                "delete Success wholeTopic=${result.deletedWholeTopic}"
                            is DeletePostResult.Failure ->
                                "delete Failure reason=${result.reason::class.simpleName}"
                        },
                    )
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "delete SessionExpired: ${error.message ?: "(no message)"}",
            )
            DeletePostResult.Failure(ReplyFailureReason.LoginRequired)
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "delete FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            DeletePostResult.Failure(ReplyFailureReason.Unknown)
        }
    }

    private fun ReplySubmitResult.toDeleteResult(): DeletePostResult = when (this) {
        // On a normal-post delete HFR refreshes to the topic (`sujet_{id}_{page}` → topicId
        // non-null). On a first-post / whole-topic delete it refreshes to the sub-category listing
        // (`liste_sujet` → no topicId), which is how we flag a whole-topic deletion. The UI only
        // offers delete on normal posts today, so `deletedWholeTopic` is a defensive read.
        is ReplySubmitResult.Success -> DeletePostResult.Success(deletedWholeTopic = topicId == null)
        is ReplySubmitResult.Failure -> DeletePostResult.Failure(reason)
    }

    private fun buildDeleteFormBody(context: EditPostContext, form: ReplyForm): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val overrides = buildMap {
            put("hash_check", form.hashCheck)
            put("verifrequet", HfrConstants.VERIF_REQUET)
            // Forward the post's current content verbatim. HFR ignores it when `delete=1` is set,
            // but sending the real form value keeps the POST shape identical to a browser submit.
            put("content_form", form.initialContent)
            put("numreponse", context.numreponse.toString())
            put("numrep", "")
            put("cat", context.cat.toString())
            put("subcat", context.subcat.toString())
            put("post", context.topicId.toString())
            put("page", context.page.toString())
            put("sujet", form.sujet)
            // THE delete flag — the one field that distinguishes this POST from an edit.
            put("delete", "1")
            form.msgIcon?.let { put("MsgIcon", it) }
        }
        val emitted = mutableSetOf<String>()
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Hard deny `password` (belt-and-braces; the parser already filters it). `delete` is set
            // explicitly above, so it can never be present here, but guard anyway for symmetry.
            if (key == "password" || key == "delete") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }

    private companion object {
        private const val LOG_TAG = "DeletePostRepository"
    }
}
