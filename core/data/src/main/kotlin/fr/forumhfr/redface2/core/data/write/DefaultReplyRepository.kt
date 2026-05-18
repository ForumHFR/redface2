package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.diagnostics.DiagnosticsLog
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
    private val diagnostics: DiagnosticsLog,
) : ReplyRepository {

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET reply form cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page}",
        )
        val html = hfrClient.getReplyForm(
            cat = context.cat,
            subcat = context.subcat,
            post = context.topicId,
            page = context.page,
        )
        return replyFormParser.parse(html).fold(
            onSuccess = { form ->
                diagnostics.record(
                    DiagnosticsLog.Level.DEBUG,
                    LOG_TAG,
                    "reply form parsed: hiddenFields=${form.hiddenFields.size} " +
                        "anonymous=${form.isAnonymous} sujet=\"${form.sujet}\"",
                )
                form
            },
            onFailure = { error ->
                // Alpha-only diagnostic snapshot: when the parser refuses the HTML
                // HFR returned, we dump a redacted excerpt to the in-app diagnostics
                // panel so a tester can copy/paste it back to a maintainer. The
                // excerpt is post-processed to mask any `hash_check=...` value that
                // might appear in the markup (even though the typical "form not
                // found" case fires before we read one).
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "reply form parse FAILED: ${error.message ?: error::class.simpleName}",
                )
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "html.length=${html.length}; head=${html.take(DIAG_HTML_HEAD).redactHashCheck()}",
                )
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "form actions=${extractFormActions(html)}",
                )
                throw error
            },
        )
    }

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
    ): ReplySubmitResult {
        // Defence in depth — the ViewModel is supposed to gate on these but we
        // re-check here so any bug upstream surfaces as a typed failure rather
        // than a malformed POST to HFR.
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
            "POST reply cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page} bbcode.length=${bbcodeContent.length}",
        )
        val formBody = buildFormBody(context, form, bbcodeContent)
        val responseHtml = hfrClient.submitReply(formBody)
        val outcome = replySubmitResponseParser.parse(responseHtml)
        when (outcome) {
            is ReplySubmitResult.Success ->
                diagnostics.record(
                    DiagnosticsLog.Level.INFO,
                    LOG_TAG,
                    "POST reply Success refreshUrl=${outcome.refreshUrl} targetPage=${outcome.targetPage}",
                )
            is ReplySubmitResult.Failure -> {
                diagnostics.record(
                    DiagnosticsLog.Level.WARN,
                    LOG_TAG,
                    "POST reply Failure reason=${outcome.reason::class.simpleName}",
                )
                if (outcome.reason == ReplyFailureReason.Unknown) {
                    diagnostics.record(
                        DiagnosticsLog.Level.WARN,
                        LOG_TAG,
                        "Unknown response head=" +
                            responseHtml.take(DIAG_HTML_HEAD).redactHashCheck(),
                    )
                }
            }
        }
        return outcome
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

    private fun extractFormActions(html: String): String {
        val matches = FORM_ACTION_REGEX.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .take(MAX_FORM_ACTIONS_DUMP)
            .toList()
        return if (matches.isEmpty()) "(none)" else matches.joinToString(", ")
    }

    /**
     * Masks any `hash_check=<value>` substring with `hash_check=<REDACTED>`. Diagnostic
     * snapshots are user-visible in the alpha diagnostics screen, so we strip the
     * token even though the typical "form not found" branch fires before reaching it.
     */
    private fun String.redactHashCheck(): String =
        HASH_CHECK_REGEX.replace(this) { "hash_check=<REDACTED>" }

    private companion object {
        private const val LOG_TAG = "ReplyRepository"
        private const val DIAG_HTML_HEAD = 600
        private const val MAX_FORM_ACTIONS_DUMP = 6
        private val FORM_ACTION_REGEX: Regex = Regex(
            """<form[^>]*action="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
        private val HASH_CHECK_REGEX: Regex = Regex(
            """hash_check[=:][^"&\s]+""",
            RegexOption.IGNORE_CASE,
        )
    }
}
