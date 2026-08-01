package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.ReplyContext
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * #604 lot 2 — the shared multi-quote materialisation (promoted verbatim from
 * PostEditorViewModel's #291 private path). Pinned contracts: selection order preserved, extras
 * fetched with quoteRef nulled, submit rides the FIRST form (hash/hidden fields), a blank prefill
 * fails the whole fetch, and a no-extra / non-quote context is a plain single fetch.
 */
class ReplyQuoteMaterializerTest {

    @Test
    fun `no extras - the first form is returned untouched`() = runBlocking {
        val repository = RecordingReplyRepository()
        val materializer = ReplyQuoteMaterializer(repository)

        val form = materializer.fetchFormWithQuotes(quoteContext(quotedNumreponse = 101), emptyList())

        assertEquals("[quotemsg=101]un[/quotemsg]", form.initialContent)
        assertEquals(1, repository.fetchedNumreponses.size)
    }

    @Test
    fun `extras are appended in selection order with quoteRef nulled`() = runBlocking {
        val repository = RecordingReplyRepository()
        val materializer = ReplyQuoteMaterializer(repository)

        val form = materializer.fetchFormWithQuotes(
            quoteContext(quotedNumreponse = 101, quoteRef = 7),
            extraQuoteNumreponses = listOf(303, 202),
        )

        assertEquals(
            "[quotemsg=101]un[/quotemsg]\n\n[quotemsg=303]un[/quotemsg]\n\n[quotemsg=202]un[/quotemsg]\n\n",
            form.initialContent,
        )
        // First fetch keeps the caller's quoteRef ; every extra nulls it (positional, 1st post only).
        assertEquals(listOf(101 to 7, 303 to null, 202 to null), repository.fetchedNumreponses)
        // The merged form still rides the FIRST form's hash.
        assertEquals("hash-101", form.hashCheck)
    }

    @Test
    fun `a blank extra prefill fails the whole fetch`() {
        val repository = RecordingReplyRepository(blankFor = setOf(202))
        val materializer = ReplyQuoteMaterializer(repository)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                materializer.fetchFormWithQuotes(
                    quoteContext(quotedNumreponse = 101),
                    extraQuoteNumreponses = listOf(202),
                )
            }
        }
    }

    @Test
    fun `a non-quote context ignores extras`() = runBlocking {
        val repository = RecordingReplyRepository()
        val materializer = ReplyQuoteMaterializer(repository)

        val form = materializer.fetchFormWithQuotes(
            ReplyContext(cat = 23, subcat = 401, topicId = 35421, page = 3),
            extraQuoteNumreponses = listOf(202),
        )

        assertEquals("", form.initialContent)
        assertEquals(1, repository.fetchedNumreponses.size)
    }

    private fun quoteContext(quotedNumreponse: Int, quoteRef: Int? = null): ReplyContext = ReplyContext(
        cat = 23,
        subcat = 401,
        topicId = 35421,
        page = 3,
        quotedNumreponse = quotedNumreponse,
        quoteRef = quoteRef,
    )
}

private class RecordingReplyRepository(
    private val blankFor: Set<Int> = emptySet(),
) : ReplyRepository {
    val fetchedNumreponses = mutableListOf<Pair<Int?, Int?>>()

    override suspend fun fetchReplyForm(context: ReplyContext): ReplyForm {
        fetchedNumreponses += context.quotedNumreponse to context.quoteRef
        val numreponse = context.quotedNumreponse
        val prefill = when {
            numreponse == null -> ""
            numreponse in blankFor -> "   "
            else -> "[quotemsg=$numreponse]un[/quotemsg]"
        }
        return ReplyForm(
            hashCheck = "hash-${numreponse ?: 0}",
            sujet = "sujet",
            hiddenFields = emptyMap(),
            isAnonymous = false,
            initialContent = prefill,
        )
    }

    override suspend fun submitReply(
        context: ReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
}
