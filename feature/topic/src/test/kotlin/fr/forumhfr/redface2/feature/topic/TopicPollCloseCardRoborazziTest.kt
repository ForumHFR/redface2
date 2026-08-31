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
 * #1201 — record-only visual review of the owner « Clore ce sondage » affordance in the three static
 * colour schemes. The PNG files are inspection artefacts, not versioned baselines. Two scenarios : an
 * OPEN poll where the owner sees the button, and a CLOSED poll where the affordance is gone (the
 * caller applies the native `poll.canClose` capability gate).
 *
 *     ./scripts/docker-dev.sh ./gradlew :feature:topic:testDebugUnitTest \
 *         --tests '*TopicPollCloseCardRoborazziTest*' --console=plain --no-daemon
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TopicPollCloseCardRoborazziTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closeCardLight() = captureTheme(darkTheme = false, amoledTheme = false, suffix = "light")

    @Test
    fun closeCardDark() = captureTheme(darkTheme = true, amoledTheme = false, suffix = "dark")

    @Test
    fun closeCardAmoled() = captureTheme(darkTheme = true, amoledTheme = true, suffix = "amoled")

    private fun captureTheme(darkTheme: Boolean, amoledTheme: Boolean, suffix: String) {
        val scenario = mutableStateOf(CloseScenario.OwnerOpen)
        compose.setContent {
            RedfaceTheme(darkTheme = darkTheme, amoledTheme = amoledTheme, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Box(modifier = Modifier.width(360.dp).padding(12.dp)) {
                        val content = scenario.value.content()
                        val onClose: () -> Unit = {}
                        TopicPollCard(
                            poll = content.poll,
                            pollVote = content.form?.let { TopicPollVoteUi(PollVoteUiState(it)) },
                            revealed = true,
                            onExpansionChanged = {},
                            onClosePoll = onClose.takeIf { content.poll.canClose },
                        )
                    }
                }
            }
        }

        CloseScenario.entries.forEach { next ->
            compose.runOnIdle { scenario.value = next }
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(
                filePath = "build/outputs/roborazzi/topic_poll_close_${next.fileName}_$suffix.png",
            )
        }
    }

    private fun CloseScenario.content(): CloseContent = when (this) {
        CloseScenario.OwnerOpen -> {
            val form = pollForm()
            CloseContent(poll = votingPoll(form).copy(canClose = true), form = form)
        }
        CloseScenario.Closed -> CloseContent(
            poll = Poll(
                question = QUESTION,
                options = listOf(
                    PollOption("Kotlin", votes = 8, percentage = 66.7f),
                    PollOption("Java", votes = 4, percentage = 33.3f),
                ),
                multipleChoice = false,
                totalVotes = 12,
                hasVoted = true,
                resultsAvailable = true,
                maxSelections = 1,
                closed = true,
            ),
            form = null,
        )
    }

    private fun pollForm(): PollVoteForm = PollVoteForm(
        hashCheck = "0123456789abcdef0123456789abcdef",
        hiddenFields = mapOf("cat" to "13", "page" to "1", "numeropost" to "84540"),
        choices = listOf("Kotlin", "Java", "Rust").mapIndexed { index, label ->
            PollVoteChoice(id = "sond${index + 1}", name = "reponse", value = "${index + 1}", label = label)
        },
        multipleChoice = false,
        maxSelections = 1,
    )

    private fun votingPoll(form: PollVoteForm): Poll = Poll(
        question = QUESTION,
        options = form.choices.map { PollOption(it.label, votes = 0, percentage = 0f) },
        multipleChoice = false,
        totalVotes = 0,
        hasVoted = false,
        resultsAvailable = false,
        maxSelections = 1,
    )

    private data class CloseContent(
        val poll: Poll,
        val form: PollVoteForm?,
    )

    private enum class CloseScenario(val fileName: String) {
        OwnerOpen("owner_open"),
        Closed("closed_no_affordance"),
    }

    private companion object {
        const val QUESTION = "Quel langage préférez-vous ?"
    }
}
