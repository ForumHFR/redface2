package fr.forumhfr.redface2.feature.topic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.write.PollVoteChoice
import fr.forumhfr.redface2.core.model.write.PollVoteForm
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #779 — record-only visual review of all poll card states in the three static colour schemes.
 * The 15 PNG files are inspection artefacts, not versioned baselines.
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:topic:testDebugUnitTest \
 *         --tests '*TopicPollVoteRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPollVoteRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pollVoteLight() = captureTheme(darkTheme = false, amoledTheme = false, suffix = "light")

    @Test
    fun pollVoteDark() = captureTheme(darkTheme = true, amoledTheme = false, suffix = "dark")

    @Test
    fun pollVoteAmoled() = captureTheme(darkTheme = true, amoledTheme = true, suffix = "amoled")

    private fun captureTheme(darkTheme: Boolean, amoledTheme: Boolean, suffix: String) {
        val scenario = mutableStateOf(PollScenario.MonoIdle)
        compose.setContent {
            RedfaceTheme(
                darkTheme = darkTheme,
                amoledTheme = amoledTheme,
                dynamicColor = false,
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.width(360.dp).padding(12.dp)) {
                        val content = scenario.value.content()
                        TopicPollCard(
                            poll = content.poll,
                            pollVote = content.pollVote?.let { TopicPollVoteUi(it) },
                            revealed = true,
                            onExpansionChanged = {},
                        )
                    }
                }
            }
        }

        PollScenario.entries.forEach { next ->
            compose.runOnIdle { scenario.value = next }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                filePath = "build/outputs/roborazzi/topic_poll_vote_${next.fileName}_$suffix.png",
            )
        }
    }

    private fun PollScenario.content(): PollContent = when (this) {
        PollScenario.MonoIdle -> {
            val form = pollForm(multipleChoice = false)
            PollContent(
                poll = votingPoll(form),
                pollVote = PollVoteUiState(form, selectedChoices = setOf(form.choices[0])),
            )
        }
        PollScenario.MultiIdle -> {
            val form = pollForm(multipleChoice = true, maxSelections = 2)
            PollContent(
                poll = votingPoll(form),
                pollVote = PollVoteUiState(form, selectedChoices = form.choices.take(2).toSet()),
            )
        }
        PollScenario.Submitting -> {
            val form = pollForm(multipleChoice = false)
            PollContent(
                poll = votingPoll(form),
                pollVote = PollVoteUiState(
                    form = form,
                    selectedChoices = setOf(form.choices[1]),
                    phase = PollVotePhase.Submitting,
                ),
            )
        }
        PollScenario.Results -> PollContent(
            poll = Poll(
                question = QUESTION,
                options = listOf(
                    PollOption("Kotlin", votes = 8, percentage = 66.7f),
                    PollOption("Java", votes = 3, percentage = 25f),
                    PollOption("Rust", votes = 1, percentage = 8.3f),
                ),
                multipleChoice = false,
                totalVotes = 12,
                hasVoted = true,
                resultsAvailable = true,
                maxSelections = 1,
            ),
            pollVote = null,
        )
        PollScenario.WithoutToken -> {
            val form = pollForm(multipleChoice = false).copy(hashCheck = "")
            PollContent(poll = votingPoll(form), pollVote = PollVoteUiState(form))
        }
    }

    private fun pollForm(
        multipleChoice: Boolean,
        maxSelections: Int? = if (multipleChoice) 2 else 1,
    ): PollVoteForm = PollVoteForm(
        hashCheck = "0123456789abcdef0123456789abcdef",
        hiddenFields = mapOf("cat" to "13", "page" to "1", "numeropost" to "84540"),
        choices = listOf("Kotlin", "Java", "Rust").mapIndexed { index, label ->
            PollVoteChoice(
                id = "sond${index + 1}",
                name = if (multipleChoice) "reponse${index + 1}" else "reponse",
                value = if (multipleChoice) "1" else "${index + 1}",
                label = label,
            )
        },
        multipleChoice = multipleChoice,
        maxSelections = maxSelections,
    )

    private fun votingPoll(form: PollVoteForm): Poll = Poll(
        question = QUESTION,
        options = form.choices.map { PollOption(it.label, votes = 0, percentage = 0f) },
        multipleChoice = form.multipleChoice,
        totalVotes = 0,
        hasVoted = false,
        resultsAvailable = false,
        maxSelections = form.maxSelections,
    )

    private data class PollContent(
        val poll: Poll,
        val pollVote: PollVoteUiState?,
    )

    private enum class PollScenario(val fileName: String) {
        MonoIdle("mono_idle"),
        MultiIdle("multi_idle"),
        Submitting("submitting"),
        Results("results_read_only"),
        WithoutToken("without_token_read_only"),
    }

    private companion object {
        const val QUESTION = "Quel langage préférez-vous ?"
    }
}
