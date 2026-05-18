package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.auth.SessionExpiredException
import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ReplyRepository {

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        val mode = if (context.isQuote) "quote" else "reply"
        diagnostics.record(
            DiagnosticsLog.Level.INFO,
            LOG_TAG,
            "GET $mode form cat=${context.cat} subcat=${context.subcat} " +
                "post=${context.topicId} page=${context.page} " +
                "numrep=${context.quotedNumreponse ?: "-"} ref=${context.quoteRef ?: "-"}",
        )
        // `HfrClient` already wraps its OkHttp call in `withContext(ioDispatcher)` (PR #162 round 3
        // round-trip), but we keep `withContext` here so the Jsoup parse (CPU-bound, ~30 KB HTML)
        // also runs off the main thread — aligned with `TopicRepositoryImpl.fetchAndPersist` which
        // brackets `client.get* + parser.parse*` as a single IO block.
        return try {
            withContext(ioDispatcher) {
                val html = hfrClient.getReplyForm(
                    cat = context.cat,
                    subcat = context.subcat,
                    post = context.topicId,
                    page = context.page,
                    quotedNumreponse = context.quotedNumreponse,
                    quoteRef = context.quoteRef,
                )
                replyFormParser.parse(html).fold(
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
                        // excerpt is post-processed to mask `hash_check` in both KV and HTML
                        // input forms (see `redactHashCheck`).
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "reply form parse FAILED: ${error.message ?: error::class.simpleName}",
                        )
                        val redactedHead = redactHashCheckForDiagnostics(
                            html.take(DIAG_HTML_HEAD),
                        )
                        diagnostics.record(
                            DiagnosticsLog.Level.WARN,
                            LOG_TAG,
                            "html.length=${html.length}; head=$redactedHead",
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
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET reply form SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            // Alpha diagnostic : surface every transport-level failure (OkHttp IOException,
            // HFR returning non-2xx, body decoding error…) with class + message before
            // rethrowing. The VM still maps to a user-facing SubmitError downstream, but
            // we want the tester to see *which* class actually fired.
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "GET reply form FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
            )
            throw error
        }
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
        // Same rationale as `fetchReplyForm` : keep network + Jsoup parse in a single IO block.
        return try {
            withContext(ioDispatcher) {
                val responseHtml = hfrClient.submitReply(formBody)
                val outcome = replySubmitResponseParser.parse(responseHtml)
                when (outcome) {
                    is ReplySubmitResult.Success ->
                        diagnostics.record(
                            DiagnosticsLog.Level.INFO,
                            LOG_TAG,
                            "POST reply Success refreshUrl=${outcome.refreshUrl} " +
                                "targetPage=${outcome.targetPage}",
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
                                    redactHashCheckForDiagnostics(responseHtml.take(DIAG_HTML_HEAD)),
                            )
                        }
                    }
                }
                outcome
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: SessionExpiredException) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST reply SessionExpired: ${error.message ?: "(no message)"}",
            )
            throw error
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            diagnostics.record(
                DiagnosticsLog.Level.WARN,
                LOG_TAG,
                "POST reply FAILED: ${error::class.simpleName}: ${error.message ?: "(no message)"}",
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
     * Assembles the POST payload. `hash_check`, `verifrequet` and `content_form`
     * are the three fields HFR validates ; everything else is forwarded verbatim
     * from the parsed form. Any local override (notably `content_form`,
     * `numreponse`, `numrep`) wins over the parsed value to keep simple-reply
     * semantics — see the `numrep` / `numreponse` notes in `protocol-hfr.md`.
     *
     * `numreponse` (post being edited) stays empty for both reply and quote.
     * `numrep` (post being cited) is empty for a simple reply, and carries the
     * cited `numreponse` for a quote — the `ReplyContext.quotedNumreponse`
     * value drives the choice. Edit will be wired in Phase 2D and will flip
     * `numreponse` instead of `numrep`.
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
            "numreponse" to "",
            // Quote: HFR identifies the cited post via `numrep`. Reply: empty.
            // The form's own hidden `numrep` (if any) is already echoed correctly
            // by HFR's reply page (always empty), and our override pins the
            // contract regardless of any future server-side change.
            "numrep" to (context.quotedNumreponse?.toString().orEmpty()),
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
            // `password` and anonymous `pseudo`, never relay `password` here.
            // We deliberately do NOT also filter `pseudo` : on an authenticated
            // form HFR carries the user's pseudo in the hidden fields and
            // expects us to echo it back on POST (mirrors HFR's own composer).
            // On an anonymous form `pseudo` is already absent from
            // `form.hiddenFields` (parser-side filter) AND `submitReply` short-
            // circuits via `guardAgainstInvalidSubmission` before reaching this
            // builder, so we never have to defend twice against the anonymous
            // shape here.
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
        // Defense-in-depth : HFR's reply form action is the bare endpoint URL today, but if a
        // future page ever inlined `hash_check` into the action URL (legacy HFR forms have been
        // observed doing so on neighbouring endpoints), we'd leak it through this diagnostic
        // line. Pipe the join result through the same redactor that already covers HTML input
        // and KV / JS forms.
        return if (matches.isEmpty()) "(none)" else redactHashCheckForDiagnostics(matches.joinToString(", "))
    }

    private companion object {
        private const val LOG_TAG = "ReplyRepository"
        private const val DIAG_HTML_HEAD = 600
        private const val MAX_FORM_ACTIONS_DUMP = 6
        private val FORM_ACTION_REGEX: Regex = Regex(
            """<form[^>]*action="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )
    }
}

/**
 * Strips every observed `hash_check` carrier from a diagnostic snapshot before it lands in
 * `DiagnosticsLog`. Public-to-the-module so the unit test in `DefaultReplyRepositoryDiagnosticsTest`
 * can pin the contract without going through the full repository plumbing. Patterns covered :
 *
 *  - query / KV : `hash_check=abc`, `hash_check:abc`, `&hash_check=abc`
 *  - JS literal : `var hash_check = "abc";` (covered by the KV form once the literal opens)
 *  - HTML input, attribute order `name` then `value`
 *    (`<input ... name="hash_check" ... value="abc" ...>`)
 *  - HTML input, attribute order `value` then `name`
 *    (`<input ... value="abc" ... name="hash_check" ...>`)
 *
 * Never logs or returns the raw value ; failure to redact is the canonical regression to test.
 */
internal fun redactHashCheckForDiagnostics(input: String): String {
    var output = HASH_CHECK_KV_REGEX.replace(input) { match ->
        // `hash_check=abc` / `hash_check:abc` — preserve the separator the caller used.
        val separator = match.value[match.value.indexOf("hash_check") + "hash_check".length]
        "hash_check${separator}<REDACTED>"
    }
    output = HASH_CHECK_INPUT_NAME_FIRST_REGEX.replace(output) { match ->
        match.value.replace(match.groupValues[1], "<REDACTED>")
    }
    output = HASH_CHECK_INPUT_VALUE_FIRST_REGEX.replace(output) { match ->
        match.value.replace(match.groupValues[1], "<REDACTED>")
    }
    return output
}

private val HASH_CHECK_KV_REGEX: Regex = Regex(
    """hash_check[=:][^"&\s<>]+""",
    RegexOption.IGNORE_CASE,
)

// `<input ... name="hash_check" ... value="VALUE" ...>`. We allow arbitrary other attributes
// between `name` and `value`. The inner `[^>]*?` stays non-greedy so we don't swallow a closing
// `>` of another tag.
private val HASH_CHECK_INPUT_NAME_FIRST_REGEX: Regex = Regex(
    """<input\b[^>]*?\bname=["']hash_check["'][^>]*?\bvalue=["']([^"']*)["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)

// `<input ... value="VALUE" ... name="hash_check" ...>`. Symmetric form for templates that
// emit `value` before `name`.
private val HASH_CHECK_INPUT_VALUE_FIRST_REGEX: Regex = Regex(
    """<input\b[^>]*?\bvalue=["']([^"']*)["'][^>]*?\bname=["']hash_check["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)
