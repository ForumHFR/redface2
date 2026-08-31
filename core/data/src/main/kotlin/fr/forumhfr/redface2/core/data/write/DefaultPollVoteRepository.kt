package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.coroutines.IoDispatcher
import fr.forumhfr.redface2.core.domain.write.PollVoteRepository
import fr.forumhfr.redface2.core.model.write.PollCloseResult
import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteFailureReason
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.model.write.PollVoteResult
import fr.forumhfr.redface2.core.network.HfrClient
import fr.forumhfr.redface2.core.parser.write.poll.PollCloseResponseParser
import fr.forumhfr.redface2.core.parser.write.poll.PollVoteResponseParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.FormBody

/** Submits the transient vote form parsed from the currently displayed topic page. */
@Singleton
class DefaultPollVoteRepository @Inject constructor(
    private val hfrClient: HfrClient,
    private val responseParser: PollVoteResponseParser,
    private val closeResponseParser: PollCloseResponseParser,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : PollVoteRepository {

    /**
     * Validates and submits one vote without retrying.
     *
     * Validation, browser-style body construction, network I/O and synchronous response parsing
     * all run on [ioDispatcher]. Every typed guard returns before [HfrClient.submitPollVote], so an
     * invalid or stale form can never cause a mutation.
     */
    override suspend fun submitPollVote(
        form: PollVoteForm,
        selectedChoices: Set<PollVoteChoice>,
    ): PollVoteResult = withContext(ioDispatcher) {
        guardAgainstInvalidSubmission(form, selectedChoices)?.let { reason ->
            return@withContext PollVoteResult.Failed(reason)
        }

        val formBody = buildFormBody(form, selectedChoices)
        responseParser.parse(hfrClient.submitPollVote(formBody))
    }

    /**
     * Submits HFR's explicit blank-vote shape without retrying.
     *
     * Unlike a normal vote, the proven live contract includes [POLL_SUBMIT] and deliberately omits
     * every `reponse*` field. The shared response parser handles the otherwise identical outcome.
     */
    override suspend fun submitBlankVote(form: PollVoteForm): PollVoteResult = withContext(ioDispatcher) {
        guardAgainstInvalidBlankSubmission(form)?.let { reason ->
            return@withContext PollVoteResult.Failed(reason)
        }

        responseParser.parse(hfrClient.submitPollVote(buildBlankFormBody(form)))
    }

    /**
     * #1201 — GET `close_sondage.php` and classify the response, all on [ioDispatcher]. No form is
     * built (the endpoint is a bare GET), no `hash_check` guard applies, and the call runs exactly
     * once : the closure is irreversible, so a retry would be at best a no-op « already closed ».
     */
    override suspend fun closePoll(cat: Int, topicId: Int): PollCloseResult = withContext(ioDispatcher) {
        closeResponseParser.parse(hfrClient.closePoll(cat, topicId))
    }

    private fun guardAgainstInvalidSubmission(
        form: PollVoteForm,
        selectedChoices: Set<PollVoteChoice>,
    ): PollVoteFailureReason? = when {
        form.hashCheck.isBlank() -> PollVoteFailureReason.InvalidHashCheck
        selectedChoices.isEmpty() -> PollVoteFailureReason.EmptySelection
        selectedChoices.any { it !in form.choices } -> PollVoteFailureReason.InvalidSelection
        !form.multipleChoice && selectedChoices.size != 1 -> PollVoteFailureReason.InvalidSelection
        form.multipleChoice &&
            form.maxSelections?.let { selectedChoices.size > it } == true ->
            PollVoteFailureReason.TooManySelections
        hasMalformedHiddenFields(form) ->
            PollVoteFailureReason.MalformedForm
        else -> null
    }

    private fun guardAgainstInvalidBlankSubmission(form: PollVoteForm): PollVoteFailureReason? = when {
        form.hashCheck.isBlank() -> PollVoteFailureReason.InvalidHashCheck
        hasMalformedHiddenFields(form) -> PollVoteFailureReason.MalformedForm
        else -> null
    }

    private fun hasMalformedHiddenFields(form: PollVoteForm): Boolean =
        REQUIRED_NUMERIC_FIELDS.any { field -> form.hiddenFields[field]?.toIntOrNull() == null }

    /**
     * Preserves HFR's observed browser order: token, hidden fields, then selected choices in the
     * original document order. The caller's [Set] iteration order is deliberately irrelevant.
     */
    private fun buildFormBody(
        form: PollVoteForm,
        selectedChoices: Set<PollVoteChoice>,
    ): FormBody = FormBody.Builder(Charsets.UTF_8)
        .add(HASH_CHECK, form.hashCheck)
        .apply {
            form.hiddenFields.forEach { (name, value) -> add(name, value) }
            form.choices.forEach { choice ->
                if (choice in selectedChoices) add(choice.name, choice.value)
            }
        }
        .build()

    /** Token + hidden fields + submit marker only: no poll choice is represented for a blank vote. */
    private fun buildBlankFormBody(form: PollVoteForm): FormBody = FormBody.Builder(Charsets.UTF_8)
        .add(HASH_CHECK, form.hashCheck)
        .apply {
            form.hiddenFields.forEach { (name, value) -> add(name, value) }
            add(POLL_SUBMIT, POLL_SUBMIT_VALUE)
        }
        .build()

    private companion object {
        private const val HASH_CHECK = "hash_check"
        private const val POLL_SUBMIT = "sondage_submit"
        private const val POLL_SUBMIT_VALUE = "Voter"
        private val REQUIRED_NUMERIC_FIELDS = listOf("cat", "page", "numeropost")
    }
}
