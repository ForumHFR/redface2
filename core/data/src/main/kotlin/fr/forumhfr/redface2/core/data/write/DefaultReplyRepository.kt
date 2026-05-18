package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.write.ReplyRepository
import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.network.HfrConstants
import fr.forumhfr.redface2.core.parser.write.ReplyFormParser
import fr.forumhfr.redface2.core.parser.write.ReplySubmitResponseParser
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.FormBody

/**
 * Default [ReplyRepository] implementation. Orchestrates the two-step HFR write
 * dance for Phase 2C-A (#145) :
 *
 * 1. GET `message.php` to obtain the per-session `hash_check` and the hidden
 *    fields HFR expects to receive verbatim.
 * 2. Build a `FormBody` from those fields + the user's BBCode content, then POST
 *    `bddpost.php` and classify the response.
 *
 * The repository deliberately does not retry — HFR's anti-flood is per-account
 * and a transparent retry would burn the rate limit. The UI handles retry on
 * user input. `hash_check` is never logged at any point.
 */
@Singleton
class DefaultReplyRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val replyFormParser: ReplyFormParser,
    private val replySubmitResponseParser: ReplySubmitResponseParser,
) : ReplyRepository {

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        val html = hfrClient.getReplyForm(
            cat = context.cat,
            subcat = context.subcat,
            post = context.topicId,
            page = context.page,
        )
        return replyFormParser.parse(html).getOrThrow()
    }

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
    ): ReplySubmitResult {
        // Defence in depth — the ViewModel is supposed to gate on these but we
        // re-check here so any bug upstream surfaces as a typed failure rather
        // than a malformed POST to HFR.
        guardAgainstInvalidSubmission(form, bbcodeContent)?.let { return it }

        val formBody = buildFormBody(context, form, bbcodeContent)
        val responseHtml = hfrClient.submitReply(formBody)
        return replySubmitResponseParser.parse(responseHtml)
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
     * Assembles the POST payload. `hash_check`, `verifrequet` and `content_form`
     * are the three fields HFR validates ; everything else is forwarded verbatim
     * from the parsed form. Any local override (notably `content_form`,
     * `numreponse`, `numrep`) wins over the parsed value to keep simple-reply
     * semantics — see the `numrep` / `numreponse` notes in `protocol-hfr.md`.
     */
    private fun buildFormBody(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
    ): FormBody {
        val builder = FormBody.Builder(Charsets.UTF_8)
        val overrides = mapOf(
            "hash_check" to form.hashCheck,
            "verifrequet" to HfrConstants.VERIF_REQUET,
            "content_form" to bbcodeContent,
            // numreponse / numrep are empty for a simple reply (no quote, no edit).
            "numreponse" to "",
            "numrep" to "",
            // Re-assert the (cat, subcat, post, page) tuple to match HFR's contract
            // even if the form ever forgets to echo them.
            "cat" to context.cat.toString(),
            "subcat" to context.subcat.toString(),
            "post" to context.topicId.toString(),
            "page" to context.page.toString(),
            "sujet" to form.sujet,
        )
        val emitted = mutableSetOf<String>()
        overrides.forEach { (key, value) ->
            builder.add(key, value)
            emitted += key
        }
        form.hiddenFields.forEach { (key, value) ->
            if (key in emitted) return@forEach
            // Belt-and-braces : even though `ReplyFormParser` already filters
            // `password` and anonymous `pseudo`, never relay them here.
            if (key == "password") return@forEach
            builder.add(key, value)
            emitted += key
        }
        return builder.build()
    }
}
