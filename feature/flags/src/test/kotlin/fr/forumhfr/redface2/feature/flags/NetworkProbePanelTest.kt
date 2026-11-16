package fr.forumhfr.redface2.feature.flags

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeOutcome
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeRequestResult
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeState
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeStatus
import fr.forumhfr.redface2.core.domain.diagnostics.NetworkProbeWave
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fr-rFR-w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NetworkProbePanelTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a single success and zero failures use French singulars`() {
        show(NetworkProbeState(waves = listOf(wave(1, 1))))

        compose.onNodeWithText(
            "Vague de 1\u00a0:\u00a01 réussite / 0 échec · connexion médiane\u00a0:\u00a0624\u00a0ms",
        ).assertIsDisplayed()
    }

    @Test
    fun `a single failure and zero successes use French singulars`() {
        show(NetworkProbeState(waves = listOf(wave(1, 0))))

        compose.onNodeWithText(
            "Vague de 1\u00a0:\u00a00 réussite / 1 échec · connexion médiane\u00a0:\u00a0indisponible",
        ).assertIsDisplayed()
    }

    @Test
    fun `success and failure plurals are selected independently`() {
        show(NetworkProbeState(waves = listOf(wave(5, 2), wave(5, 4))))

        compose.onNodeWithText("2 réussites / 3 échecs", substring = true).assertIsDisplayed()
        compose.onNodeWithText("4 réussites / 1 échec", substring = true).assertIsDisplayed()
    }

    @Test
    fun `completion precedes all results and availability follows the warning`() {
        show(
            NetworkProbeState(
                status = NetworkProbeStatus.COMPLETE,
                waves = listOf(wave(1, 1), wave(5, 5, disturbed = true)),
            ),
            cooldownSeconds = 49,
        )

        val messages = listOf(
            "Test terminé.", "Vague de 1", "Vague de 5", "Mesure perturbée", "Test disponible dans 49 s",
        )
        val bounds = messages.map { compose.onNodeWithText(it, substring = true).fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEach { (before, after) ->
            assertTrue("status, results, warning, then availability", before.bottom <= after.top)
        }
        compose.onNodeWithText("Tester la connexion").assertIsNotEnabled()
    }

    @Test
    fun `median label value and unit stay on the same line at Pixel 8 width`() {
        show(NetworkProbeState(waves = listOf(wave(1, 1), wave(5, 5), wave(10, 10), wave(20, 20))))

        for (size in listOf(1, 5, 10, 20)) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("Vague de $size\u00a0:", substring = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            val medianStart = text.indexOf("médiane")
            assertTrue(medianStart >= 0)
            assertEquals(layout.getLineForOffset(medianStart), layout.getLineForOffset(text.lastIndex))
        }
    }

    @Test
    fun `start label aligns with the explanation and starts the test`() {
        var started = 0
        var explanation = ""
        compose.setContent {
            MaterialTheme {
                explanation = stringResource(R.string.diagnostics_probe_explanation)
                NetworkProbePanel(NetworkProbeState(), 0, onStart = { started++ }, onCancel = {})
            }
        }

        val label = compose.onNodeWithText("Tester la connexion", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val paragraph = compose.onNodeWithText(explanation).fetchSemanticsNode().boundsInRoot
        assertEquals(paragraph.left, label.left, 0.5f)
        compose.onNodeWithText("Tester la connexion").performClick()
        assertEquals(1, started)
    }

    @Test
    fun `running single request uses singular and keeps cancellation available`() {
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                NetworkProbePanel(
                    NetworkProbeState(status = NetworkProbeStatus.RUNNING, waveSize = 1),
                    cooldownSeconds = 45,
                    onStart = {},
                    onCancel = { cancelled++ },
                )
            }
        }

        compose.onNodeWithText("Test en cours — vague de 1 requête…").assertIsDisplayed()
        compose.onNodeWithText("Test disponible", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Tester la connexion").assertIsNotEnabled()
        compose.onNodeWithText("Annuler").performClick()
        assertEquals(1, cancelled)
    }

    private fun show(state: NetworkProbeState, cooldownSeconds: Long = 0) {
        compose.setContent {
            MaterialTheme {
                NetworkProbePanel(state, cooldownSeconds, onStart = {}, onCancel = {})
            }
        }
    }

    private fun wave(size: Int, successes: Int, disturbed: Boolean = false) = NetworkProbeWave(
        size = size,
        results = List(size) { index ->
            NetworkProbeRequestResult(
                tcpMs = 124,
                tlsMs = 500,
                outcome = if (index < successes) NetworkProbeOutcome.OK else NetworkProbeOutcome.NETWORK_ERROR,
            )
        },
        disturbed = disturbed,
    )
}
