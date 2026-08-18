package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyForm

/**
 * Materialises private-message quotes through one sequential form GET per selection (#1074).
 *
 * Unlike the topic contract, no MP capture proves that HFR can resolve a quote from `numrep`
 * alone. Every request therefore keeps the selection's own source page and mandatory `ref`. The
 * first fetched form remains the submission form (hash and hidden fields); only its
 * [ReplyForm.initialContent] is replaced by the server-prefilled quote blocks concatenated in
 * selection order.
 *
 * A blank prefill fails the whole operation. Silently dropping one explicitly selected private
 * message would otherwise let the user submit a reply without the citation they asked for.
 */
class PrivateMessageReplyQuoteMaterializer(
    private val repository: PrivateMessageWriteRepository,
) {

    suspend fun fetchFormWithQuotes(
        context: PrivateMessageReplyContext,
        selections: List<QuoteSelection>,
        allowEmbeddedFallback: Boolean,
    ): MaterializedPrivateMessageReplyForm {
        require(context.quote == null || selections.isEmpty()) {
            "A simple private-message quote cannot be combined with a multi-quote selection"
        }
        val quoteContexts = selections.map { selection -> context.forSelection(selection) }
        val submitContext = quoteContexts.firstOrNull() ?: context
        val firstForm = repository.fetchReplyForm(submitContext, allowEmbeddedFallback)
        if (quoteContexts.isEmpty()) {
            if (context.quote != null) requireQuotePrefill(firstForm.initialContent)
            return MaterializedPrivateMessageReplyForm(firstForm, submitContext)
        }

        requireQuotePrefill(firstForm.initialContent)
        val prefills = mutableListOf(firstForm.initialContent)
        quoteContexts.drop(1).forEach { quoteContext ->
            val prefill = repository.fetchReplyForm(quoteContext, allowEmbeddedFallback).initialContent
            requireQuotePrefill(prefill)
            prefills += prefill
        }
        return MaterializedPrivateMessageReplyForm(
            form = firstForm.copy(initialContent = prefills.joinToString(separator = "\n")),
            submitContext = submitContext,
        )
    }

    private fun PrivateMessageReplyContext.forSelection(
        selection: QuoteSelection,
    ): PrivateMessageReplyContext {
        val locator = selection.locator
        val ref = requireNotNull(locator.ref) {
            "A private-message quote selection requires a server-provided ref"
        }
        return copy(
            page = locator.page,
            quote = PrivateMessageQuote(numreponse = locator.numreponse, ref = ref),
        )
    }

    private fun requireQuotePrefill(prefill: String) {
        check(prefill.isNotBlank()) { "private-message quote prefill came back blank" }
    }
}

/** The first quote's typed context must accompany the form through submission validation. */
data class MaterializedPrivateMessageReplyForm(
    val form: ReplyForm,
    val submitContext: PrivateMessageReplyContext,
)
