package fr.forumhfr.redface2.core.domain.write

import fr.forumhfr.redface2.core.model.write.PrivateMessageQuote
import fr.forumhfr.redface2.core.model.write.PrivateMessageReplyContext
import fr.forumhfr.redface2.core.model.write.QuoteLocator
import fr.forumhfr.redface2.core.model.write.QuoteSelection
import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PrivateMessageReplyQuoteMaterializerTest {

    @Test
    fun `plain reply fetches its original context once`() = runBlocking {
        val repository = RecordingPrivateMessageWriteRepository()
        val materializer = PrivateMessageReplyQuoteMaterializer(repository)

        val result = materializer.fetchFormWithQuotes(replyContext, emptyList(), true)

        assertEquals(replyContext, result.submitContext)
        assertEquals(listOf(replyContext), repository.fetchedContexts)
        assertEquals("hash-reply", result.form.hashCheck)
    }

    @Test
    fun `simple quote keeps its page and ref and fails on a blank prefill`() {
        val quoteContext = replyContext.copy(
            page = 4,
            quote = PrivateMessageQuote(numreponse = 101, ref = 7),
        )
        val repository = RecordingPrivateMessageWriteRepository(blankFor = setOf(101))
        val materializer = PrivateMessageReplyQuoteMaterializer(repository)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { materializer.fetchFormWithQuotes(quoteContext, emptyList(), true) }
        }
        assertEquals(listOf(quoteContext), repository.fetchedContexts)
    }

    @Test
    fun `multi quote fetches sequentially with every locator and rides the first form`() = runBlocking {
        val repository = RecordingPrivateMessageWriteRepository()
        val materializer = PrivateMessageReplyQuoteMaterializer(repository)
        val selections = listOf(
            selection(numreponse = 303, page = 8, ref = 2),
            selection(numreponse = 101, page = 3, ref = 9),
            selection(numreponse = 202, page = 5, ref = 4),
        )

        val result = materializer.fetchFormWithQuotes(replyContext, selections, true)

        assertEquals(
            listOf(303 to (8 to 2), 101 to (3 to 9), 202 to (5 to 4)),
            repository.fetchedContexts.map { fetched ->
                val quote = requireNotNull(fetched.quote)
                quote.numreponse to (fetched.page to quote.ref)
            },
        )
        assertEquals(repository.fetchedContexts.first(), result.submitContext)
        assertEquals("hash-303", result.form.hashCheck)
        assertEquals(mapOf("first" to "303"), result.form.hiddenFields)
        assertEquals(
            "[quotemsg=303,2,1]trois[/quotemsg]\n\n" +
                "[quotemsg=101,9,1]un[/quotemsg]\n\n" +
                "[quotemsg=202,4,1]deux[/quotemsg]\n",
            result.form.initialContent,
        )
    }

    @Test
    fun `a blank additional prefill fails the whole materialisation`() {
        val repository = RecordingPrivateMessageWriteRepository(blankFor = setOf(202))
        val materializer = PrivateMessageReplyQuoteMaterializer(repository)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                materializer.fetchFormWithQuotes(
                    replyContext,
                    listOf(
                        selection(numreponse = 101, page = 2, ref = 1),
                        selection(numreponse = 202, page = 6, ref = 3),
                        selection(numreponse = 303, page = 8, ref = 5),
                    ),
                    true,
                )
            }
        }
        assertEquals(listOf(101, 202), repository.fetchedContexts.map { it.quote?.numreponse })
    }

    @Test
    fun `a missing private-message ref fails before any request`() {
        val repository = RecordingPrivateMessageWriteRepository()
        val materializer = PrivateMessageReplyQuoteMaterializer(repository)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                materializer.fetchFormWithQuotes(
                    replyContext,
                    listOf(selection(numreponse = 101, page = 2, ref = null)),
                    true,
                )
            }
        }
        assertEquals(emptyList<PrivateMessageReplyContext>(), repository.fetchedContexts)
    }

    private fun selection(numreponse: Int, page: Int, ref: Int?): QuoteSelection = QuoteSelection(
        locator = QuoteLocator(page = page, numreponse = numreponse, ref = ref),
        author = "Auteur",
        excerpt = "Extrait",
    )

    private val replyContext = PrivateMessageReplyContext(threadId = 4_242_424, page = 6)
}

private class RecordingPrivateMessageWriteRepository(
    private val blankFor: Set<Int> = emptySet(),
) : PrivateMessageWriteRepository {
    val fetchedContexts = mutableListOf<PrivateMessageReplyContext>()

    override suspend fun fetchReplyForm(
        context: PrivateMessageReplyContext,
        allowEmbeddedFallback: Boolean,
    ): ReplyForm {
        fetchedContexts += context
        val quote = context.quote
        val initialContent = when {
            quote == null -> ""
            quote.numreponse in blankFor -> "   "
            else -> {
                val word = mapOf(101 to "un", 202 to "deux", 303 to "trois").getValue(quote.numreponse)
                "[quotemsg=${quote.numreponse},${quote.ref},1]$word[/quotemsg]\n"
            }
        }
        return ReplyForm(
            hashCheck = "hash-${quote?.numreponse ?: "reply"}",
            sujet = "Sujet",
            hiddenFields = quote?.let { mapOf("first" to it.numreponse.toString()) }.orEmpty(),
            isAnonymous = false,
            initialContent = initialContent,
        )
    }

    override suspend fun submitReply(
        context: PrivateMessageReplyContext,
        form: ReplyForm,
        bbcodeContent: String,
        options: ReplyFormOptions,
        recipientsOverride: String?,
    ): ReplySubmitResult = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)

    override suspend fun fetchComposeForm(prefilledRecipient: String?): ReplyForm = error("not used")

    override suspend fun submitNewMessage(
        form: ReplyForm,
        recipients: String,
        subject: String,
        bbcodeContent: String,
        options: ReplyFormOptions,
    ): ReplySubmitResult = error("not used")
}
