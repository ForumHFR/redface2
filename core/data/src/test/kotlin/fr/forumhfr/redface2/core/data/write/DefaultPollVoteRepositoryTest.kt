package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteFailureReason
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.model.write.PollVoteResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.poll.PollVoteResponseParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultPollVoteRepositoryTest {
    private val hfrClient = mockk<HfrClient>()
    private val responseParser = PollVoteResponseParser()
    private val repository = DefaultPollVoteRepository(
        hfrClient = hfrClient,
        responseParser = responseParser,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `single-choice payload preserves exact hidden-field and radio order`() = runTest {
        val form = singleChoiceForm()
        val submittedBody = slot<FormBody>()
        coEvery { hfrClient.submitPollVote(capture(submittedBody)) } returns ACCEPTED_HTML

        val result = repository.submitPollVote(form, setOf(form.choices[1]))

        assertEquals(PollVoteResult.Accepted, result)
        assertEquals(
            listOf(
                "hash_check" to HASH_CHECK,
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "557",
                "numeropost" to "96127",
                "reponse" to "2",
            ),
            submittedBody.captured.fields(),
        )
        coVerify(exactly = 1) { hfrClient.submitPollVote(any()) }
    }

    @Test
    fun `multiple-choice payload reorders a disordered Set by original form choices`() = runTest {
        val form = multipleChoiceForm()
        val submittedBody = slot<FormBody>()
        coEvery { hfrClient.submitPollVote(capture(submittedBody)) } returns ACCEPTED_HTML

        // Deliberately insert choice 3 before choice 1. Set iteration order must not leak to wire.
        val result = repository.submitPollVote(form, linkedSetOf(form.choices[2], form.choices[0]))

        assertEquals(PollVoteResult.Accepted, result)
        assertEquals(
            listOf(
                "hash_check" to HASH_CHECK,
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "426",
                "numeropost" to "181",
                "reponse1" to "1",
                "reponse3" to "1",
            ),
            submittedBody.captured.fields(),
        )
    }

    @Test
    fun `every invalid submission guard returns a typed failure without POST`() = runTest {
        val mono = singleChoiceForm()
        val foreignChoice = PollVoteChoice("foreign", "reponse", "99", "Foreign")
        val cases = mutableListOf<GuardCase>()
        cases += GuardCase(
            PollVoteFailureReason.InvalidHashCheck,
            mono.copy(hashCheck = "   "),
            setOf(mono.choices.first()),
        )
        cases += GuardCase(PollVoteFailureReason.EmptySelection, mono, emptySet())
        cases += GuardCase(PollVoteFailureReason.InvalidSelection, mono, setOf(foreignChoice))
        cases += GuardCase(PollVoteFailureReason.InvalidSelection, mono, mono.choices.take(2).toSet())

        val cappedMulti = multipleChoiceForm().copy(maxSelections = 1)
        cases += GuardCase(
            PollVoteFailureReason.TooManySelections,
            cappedMulti,
            cappedMulti.choices.take(2).toSet(),
        )

        REQUIRED_NUMERIC_FIELDS.forEach { field ->
            cases += GuardCase(
                PollVoteFailureReason.MalformedForm,
                mono.copy(hiddenFields = mono.hiddenFields - field),
                setOf(mono.choices.first()),
            )
            cases += GuardCase(
                PollVoteFailureReason.MalformedForm,
                mono.copy(hiddenFields = mono.hiddenFields + (field to "not-a-number")),
                setOf(mono.choices.first()),
            )
        }

        cases.forEach { case ->
            assertEquals(
                "wrong guard result for form=${case.form} selection=${case.selectedChoices}",
                PollVoteResult.Failed(case.reason),
                repository.submitPollVote(case.form, case.selectedChoices),
            )
        }
        coVerify(exactly = 0) { hfrClient.submitPollVote(any()) }
    }

    @Test
    fun `response HTML maps to accepted already-voted and unexpected results`() = runTest {
        val form = singleChoiceForm()
        coEvery { hfrClient.submitPollVote(any()) } returnsMany listOf(
            ACCEPTED_HTML,
            "<div class=\"hop\">Désolé, vous avez déjà voté !</div>",
            "<div class=\"hop\">Réponse inconnue.</div>",
        )

        assertEquals(PollVoteResult.Accepted, repository.submitPollVote(form, setOf(form.choices[0])))
        assertEquals(PollVoteResult.AlreadyVoted, repository.submitPollVote(form, setOf(form.choices[0])))
        assertEquals(
            PollVoteResult.Failed(PollVoteFailureReason.UnexpectedResponse),
            repository.submitPollVote(form, setOf(form.choices[0])),
        )
        coVerify(exactly = 3) { hfrClient.submitPollVote(any()) }
    }

    @Test
    fun `body construction POST and response parsing run on injected IO dispatcher`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, IO_THREAD_NAME)
        }
        val dispatcher = executor.asCoroutineDispatcher()
        val client = mockk<HfrClient>()
        val parser = mockk<PollVoteResponseParser>()
        val threadedRepository = DefaultPollVoteRepository(client, parser, dispatcher)
        try {
            coEvery { client.submitPollVote(any()) } coAnswers {
                assertOnIoThread()
                ACCEPTED_HTML
            }
            every { parser.parse(any()) } answers {
                assertOnIoThread()
                PollVoteResult.Accepted
            }

            val form = singleChoiceForm()
            val result = runBlocking {
                threadedRepository.submitPollVote(form, setOf(form.choices.first()))
            }

            assertEquals(PollVoteResult.Accepted, result)
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun singleChoiceForm(): PollVoteForm {
        val choices = listOf(
            PollVoteChoice("sond1", "reponse", "1", "First"),
            PollVoteChoice("sond2", "reponse", "2", "Second"),
        )
        return PollVoteForm(
            hashCheck = HASH_CHECK,
            hiddenFields = linkedMapOf(
                "cat" to "13",
                "p" to "1",
                "page" to "1",
                "sondage" to "1",
                "owntopic" to "0",
                "subcat" to "557",
                "numeropost" to "96127",
            ),
            choices = choices,
            multipleChoice = false,
            maxSelections = 1,
        )
    }

    private fun multipleChoiceForm(): PollVoteForm = PollVoteForm(
        hashCheck = HASH_CHECK,
        hiddenFields = linkedMapOf(
            "cat" to "13",
            "p" to "1",
            "page" to "1",
            "sondage" to "1",
            "owntopic" to "0",
            "subcat" to "426",
            "numeropost" to "181",
        ),
        choices = (1..3).map { index ->
            PollVoteChoice("sond$index", "reponse$index", "1", "Choice $index")
        },
        multipleChoice = true,
        maxSelections = 2,
    )

    // kotlinx-coroutines appends a « @coroutine#N » suffix to the thread name while a continuation
    // runs, so match the injected dispatcher thread by prefix rather than exact equality.
    private fun assertOnIoThread() {
        val threadName = Thread.currentThread().name
        assertTrue(
            "must run on the injected IO dispatcher thread but was $threadName",
            threadName.startsWith(IO_THREAD_NAME),
        )
    }

    private fun FormBody.fields(): List<Pair<String, String>> =
        (0 until size).map { index -> name(index) to value(index) }

    private data class GuardCase(
        val reason: PollVoteFailureReason,
        val form: PollVoteForm,
        val selectedChoices: Set<PollVoteChoice>,
    )

    private companion object {
        private const val HASH_CHECK = "00000000000000000000000000000000"
        private const val IO_THREAD_NAME = "poll-vote-io"
        private const val ACCEPTED_HTML =
            "<div class=\"hop\">Votre vote a bien été pris en compte !</div>"
        private val REQUIRED_NUMERIC_FIELDS = listOf("cat", "page", "numeropost")
    }
}
