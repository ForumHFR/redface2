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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #779 — Compose JVM contract for the real Topic poll card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
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
    fun `results keep the historical read-only rendering`() {
        setResultsCard()

        compose.onNodeWithText("Kotlin — 80", substring = true).assertIsDisplayed()
        compose.onNodeWithText("10 votes au total • choix unique").assertIsDisplayed()
        compose.onNodeWithText("Voter").assertDoesNotExist()
    }

    @Test
    fun `blank token makes the whole form read-only`() {
        val form = pollForm(multipleChoice = false).copy(hashCheck = "")
        setCard(form = form, state = PollVoteUiState(form = form))

        compose.onNode(roleWithText(Role.RadioButton, "Kotlin")).assertIsNotEnabled()
        compose.onNodeWithText("Voter").assertIsNotEnabled()
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

    private fun setCard(
        form: PollVoteForm,
        state: PollVoteUiState,
        onSelectionChanged: (PollVoteChoice, Boolean) -> Unit = { _, _ -> },
        onExpansionChanged: (Boolean) -> Unit = {},
    ) {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPollCard(
                    poll = votingPoll(form),
                    pollVote = TopicPollVoteUi(state = state, onSelectionChanged = onSelectionChanged),
                    revealed = true,
                    onExpansionChanged = onExpansionChanged,
                )
            }
        }
    }

    private fun setResultsCard() {
        compose.setContent {
            RedfaceTheme(darkTheme = false, amoledTheme = false, dynamicColor = false) {
                TopicPollCard(
                    poll = Poll(
                        question = "Quel langage préférez-vous ?",
                        options = listOf(
                            PollOption("Kotlin", votes = 8, percentage = 80f),
                            PollOption("Java", votes = 2, percentage = 20f),
                        ),
                        multipleChoice = false,
                        totalVotes = 10,
                        hasVoted = true,
                        resultsAvailable = true,
                        maxSelections = 1,
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
}
