package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #779/#1170 — Compose JVM contract for the real Topic poll card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPollVoteCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `single-choice form renders radios and routes an option tap without collapsing`() {
        val form = pollForm(multipleChoice = false)
        var selected: Pair<PollVoteChoice, Boolean>? = null
        var expansionToggles = 0
        setCard(
            form = form,
            state = PollVoteUiState(form = form, selectedChoices = setOf(form.choices[0])),
            onSelectionChanged = { choice, checked -> selected = choice to checked },
            onExpansionChanged = { expansionToggles++ },
        )

        compose.onNode(roleWithText(Role.RadioButton, "Java"))
            .assertIsDisplayed()
            .performClick()

        assertEquals(form.choices[1] to true, selected)
        assertEquals("the option click must not toggle card expansion", 0, expansionToggles)
        compose.onNodeWithText("Voter").assertIsEnabled()
        compose.onNodeWithText("Voter blanc").assertIsNotEnabled()
    }

    @Test
    fun `tapping the selected single-choice radio routes a deselection`() {
        val form = pollForm(multipleChoice = false)
        var selected: Pair<PollVoteChoice, Boolean>? = null
        setCard(
            form = form,
            state = PollVoteUiState(form = form, selectedChoices = setOf(form.choices[0])),
            onSelectionChanged = { choice, checked -> selected = choice to checked },
        )

        compose.onNode(roleWithText(Role.RadioButton, "Kotlin")).performClick()

        assertEquals(form.choices[0] to false, selected)
    }

    @Test
    fun `multiple-choice form renders checkboxes and its known bound`() {
        val form = pollForm(multipleChoice = true, maxSelections = 2)
        var selected: Pair<PollVoteChoice, Boolean>? = null
        setCard(
            form = form,
            state = PollVoteUiState(form = form),
            onSelectionChanged = { choice, checked -> selected = choice to checked },
        )

        compose.onNode(roleWithText(Role.Checkbox, "Kotlin"))
            .assertIsDisplayed()
            .performClick()

        assertEquals(form.choices[0] to true, selected)
        compose.onNodeWithText("2 choix maximum").assertIsDisplayed()
    }

    @Test
    fun `results render accessible percentage bars`() {
        setResultsCard()

        compose.onNodeWithText("Kotlin").assertIsDisplayed()
        compose.onNodeWithText("8 votes").assertIsDisplayed()
        compose.onNodeWithText("80\u00A0%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kotlin, 8 votes, 80\u00A0%").assertIsDisplayed()
        compose.onNodeWithText("10 votes au total • choix unique").assertIsDisplayed()
        compose.onNodeWithText("Voter").assertDoesNotExist()
    }

    @Test
    fun `results show blank votes as a dedicated bar row`() {
        setResultsCard(blankVotes = 2)

        compose.onNodeWithText("10 votes au total • choix unique").assertIsDisplayed()
        compose.onNodeWithText("Vote blanc").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kotlin, 6 votes, 60\u00A0%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vote blanc, 2 votes, 20\u00A0%").assertIsDisplayed()
    }

    @Test
    fun `open expiration uses the server state wording`() {
        val form = pollForm(multipleChoice = false)
        setCard(
            form = form,
            state = PollVoteUiState(form = form),
            poll = votingPoll(form).copy(expiresAt = EXPIRATION),
        )

        compose.onNodeWithText("Expire le", substring = true).assertIsDisplayed()
        compose.onNodeWithText("A expiré le", substring = true).assertDoesNotExist()
    }

    @Test
    fun `closed poll shows status and expiration but stays read-only`() {
        val form = pollForm(multipleChoice = false)
        setCard(
            form = form,
            state = PollVoteUiState(form = form),
            poll = votingPoll(form).copy(closed = true, expiresAt = EXPIRATION),
        )

        compose.onNodeWithText("Sondage clos").assertIsDisplayed()
        compose.onNodeWithText("A expiré le", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Expire le", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Voter").assertDoesNotExist()
        compose.onNodeWithText("Voter blanc").assertDoesNotExist()
    }

    @Test
    fun `blank token makes the whole form read-only`() {
        val form = pollForm(multipleChoice = false).copy(hashCheck = "")
        setCard(form = form, state = PollVoteUiState(form = form))

        compose.onNode(roleWithText(Role.RadioButton, "Kotlin")).assertIsNotEnabled()
        compose.onNodeWithText("Voter").assertIsNotEnabled()
        compose.onNodeWithText("Voter blanc").assertIsNotEnabled()
        compose.onNodeWithText("Vote indisponible. Actualisez la page ou reconnectez-vous.")
            .assertIsDisplayed()
    }

    @Test
    fun `vote button is disabled without a selection`() {
        val form = pollForm(multipleChoice = false)
        setCard(form = form, state = PollVoteUiState(form = form))
        compose.onNodeWithText("Voter").assertIsNotEnabled()
    }

    @Test
    fun `blank vote button is enabled only for an empty live selection and routes its tap`() {
        val form = pollForm(multipleChoice = false)
        var blankVotes = 0
        setCard(
            form = form,
            state = PollVoteUiState(form = form),
            onBlankVote = { blankVotes++ },
        )

        compose.onNodeWithText("Voter blanc")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, blankVotes)
        compose.onNodeWithText("Voter").assertIsNotEnabled()
    }

    @Test
    fun `vote button and choices are disabled throughout a mutation`() {
        val form = pollForm(multipleChoice = false)
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPollCard(
                    poll = votingPoll(form),
                    pollVote = TopicPollVoteUi(
                        state = PollVoteUiState(
                            form = form,
                            selectedChoices = setOf(form.choices[0]),
                            phase = PollVotePhase.Submitting,
                        ),
                    ),
                    revealed = true,
                    onExpansionChanged = {},
                )
            }
        }
        compose.onNodeWithText("Voter").assertIsNotEnabled()
        compose.onNodeWithText("Voter blanc").assertIsNotEnabled()
        compose.onNode(roleWithText(Role.RadioButton, "Kotlin")).assertIsNotEnabled()
        compose.onNodeWithText("Envoi du vote…").assertIsDisplayed()
    }

    @Test
    fun `typed error is rendered from the French resource`() {
        val form = pollForm(multipleChoice = false)
        setCard(
            form = form,
            state = PollVoteUiState(
                form = form,
                selectedChoices = setOf(form.choices[0]),
                error = PollVoteUiError.Network,
            ),
        )

        compose.onNodeWithText("Erreur réseau. Votre sélection est conservée.").assertIsDisplayed()
    }

    @Test
    fun `owner of an open poll sees the close affordance and its tap is routed`() {
        val form = pollForm(multipleChoice = false)
        var closeTaps = 0
        setCard(
            form = form,
            state = PollVoteUiState(form = form),
            canClosePoll = true,
            onClosePoll = { closeTaps++ },
        )

        compose.onNodeWithText("Clore ce sondage")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, closeTaps)
    }

    @Test
    fun `the close affordance is absent when the caller did not grant it`() {
        val form = pollForm(multipleChoice = false)
        setCard(form = form, state = PollVoteUiState(form = form), canClosePoll = false)

        compose.onNodeWithText("Clore ce sondage").assertDoesNotExist()
    }

    @Suppress("LongParameterList") // Render harness: each argument controls one card knob, all defaulted.
    private fun setCard(
        form: PollVoteForm,
        state: PollVoteUiState,
        onSelectionChanged: (PollVoteChoice, Boolean) -> Unit = { _, _ -> },
        onBlankVote: () -> Unit = {},
        onExpansionChanged: (Boolean) -> Unit = {},
        poll: Poll = votingPoll(form),
        canClosePoll: Boolean = false,
        onClosePoll: () -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPollCard(
                    poll = poll,
                    pollVote = TopicPollVoteUi(
                        state = state,
                        onSelectionChanged = onSelectionChanged,
                        onBlankVote = onBlankVote,
                    ),
                    revealed = true,
                    onExpansionChanged = onExpansionChanged,
                    onClosePoll = onClosePoll.takeIf { canClosePoll },
                )
            }
        }
    }

    private fun setResultsCard(blankVotes: Int? = null) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                val optionVotes = if (blankVotes == null) listOf(8, 2) else listOf(6, 2)
                TopicPollCard(
                    poll = Poll(
                        question = "Quel langage préférez-vous ?",
                        options = listOf(
                            PollOption("Kotlin", votes = optionVotes[0], percentage = 80f),
                            PollOption("Java", votes = optionVotes[1], percentage = 20f),
                        ),
                        multipleChoice = false,
                        totalVotes = optionVotes.sum() + (blankVotes ?: 0),
                        hasVoted = true,
                        resultsAvailable = true,
                        maxSelections = 1,
                        blankVotes = blankVotes,
                    ),
                    revealed = true,
                    onExpansionChanged = {},
                )
            }
        }
    }

    private fun roleWithText(role: Role, text: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.Role, role) and hasText(text)

    private fun pollForm(multipleChoice: Boolean, maxSelections: Int? = if (multipleChoice) 2 else 1) =
        PollVoteForm(
            hashCheck = "0123456789abcdef0123456789abcdef",
            hiddenFields = mapOf("cat" to "13", "page" to "1", "numeropost" to "84540"),
            choices = listOf(
                PollVoteChoice("sond1", if (multipleChoice) "reponse1" else "reponse", "1", "Kotlin"),
                PollVoteChoice("sond2", if (multipleChoice) "reponse2" else "reponse", "2", "Java"),
            ),
            multipleChoice = multipleChoice,
            maxSelections = maxSelections,
        )

    private fun votingPoll(form: PollVoteForm): Poll = Poll(
        question = "Quel langage préférez-vous ?",
        options = form.choices.map { PollOption(it.label, votes = 0, percentage = 0f) },
        multipleChoice = form.multipleChoice,
        totalVotes = 0,
        hasVoted = false,
        resultsAvailable = false,
        maxSelections = form.maxSelections,
    )

    private companion object {
        val EXPIRATION: LocalDateTime = LocalDateTime.of(2026, 8, 30, 18, 45)
    }
}
