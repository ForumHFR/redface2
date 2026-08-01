package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #291 multi-quote, promoted to a shared collaborator for the Postage wave (#604 lot 2, cadrage
 * Codex : ONE implementation of « fetch N quote forms + concat + ride the first form's hash »,
 * consumed by both the full-screen editor and the quick-reply sheet).
 *
 * The quote form fetch (#146) returns ONE `[quotemsg]` prefill per `numrep`, so additional quoted
 * posts are fetched by replaying the same contract with `quotedNumreponse` swapped, then
 * concatenated into the first form's [ReplyForm.initialContent] in selection order. Client-side
 * only: HFR never sees a multi-numrep request, and the submit still rides the FIRST form's
 * `hash_check`/hidden fields (per-session, not per-post).
 *
 * Sequential on purpose: N is tiny (a handful of posts), order must be deterministic, and a failed
 * extra fails the whole fetch — silently dropping a quote the user explicitly selected would be
 * worse than the retryable form-fetch error.
 */
@Singleton
class ReplyQuoteMaterializer @Inject constructor(
    private val replyRepository: ReplyRepository,
) {

    /**
     * Fetch the reply form for [context], appending one `[quotemsg]` prefill per entry of
     * [extraQuoteNumreponses] (selection order preserved). With no extras — or a non-quote
     * context — this is a plain [ReplyRepository.fetchReplyForm].
     */
    suspend fun fetchFormWithQuotes(context: ReplyContext, extraQuoteNumreponses: List<Int>): ReplyForm {
        val form = replyRepository.fetchReplyForm(context)
        return when {
            !context.isQuote -> form

            // #583 — the SINGLE-quote path gets the same blank-prefill guard the multi-quote loop
            // always had : HFR resolves the prefill by `numrep` alone (contract proven live
            // 2026-07-12 — page/p are ignored), so a 200-OK form with a BLANK prefill means the
            // quote was NOT materialised (unresolved numrep, session edge, markup change). Failing
            // the fetch keeps the caller's retryable error path — posting the reply SILENTLY
            // without its quote block was the reported symptom.
            extraQuoteNumreponses.isEmpty() -> {
                check(form.initialContent.isNotBlank()) { "quote prefill came back blank" }
                form
            }

            else -> form.copy(initialContent = mergedPrefills(form, context, extraQuoteNumreponses))
        }
    }

    private suspend fun mergedPrefills(
        form: ReplyForm,
        context: ReplyContext,
        extraQuoteNumreponses: List<Int>,
    ): String {
        val prefills = buildList {
            add(form.initialContent)
            extraQuoteNumreponses.forEach { numreponse ->
                // quoteRef is positional/cosmetic and belongs to the FIRST post only.
                add(
                    replyRepository
                        .fetchReplyForm(context.copy(quotedNumreponse = numreponse, quoteRef = null))
                        .initialContent,
                )
            }
        }
        return prefills
            .map { prefill ->
                prefill.trimEnd().also { trimmed ->
                    // Codex review — a 200-OK form whose prefill came back BLANK would silently
                    // drop a quote the user explicitly selected (the exact failure mode the
                    // sequential design refuses). Fail the whole fetch instead; callers keep
                    // their retryable error path.
                    check(trimmed.isNotBlank()) { "multi-quote prefill came back blank" }
                }
            }
            .joinToString(separator = "\n\n", postfix = "\n\n")
    }
}
