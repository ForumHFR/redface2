package fr.forumhfr.redface2.core.ui.post

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #960 N1 — the reservation RE-ARM seam of [rememberPainterAttempt].
 *
 * Two occurrences of the same url race for the single painter reservation of a generation. The
 * loser is denied; when the WINNER is disposed before settling, [MediaAttemptLedger
 * .rollbackReservation] returns the axis to untried WITHOUT bumping the generation (lock #5 —
 * a late rollback must never clobber a fresh generation). Neither the memoized attempt nor
 * `failedFresh` moves on that rollback, so before this fix no key of the loser's reservation
 * effect changed: the surviving occurrence stayed on its placeholder for the lifetime of the
 * screen, unreachable by the refresh gesture (scoped to FAILED axes, lock #1 — pinned in
 * `MediaAttemptLedgerTest`).
 */
@RunWith(RobolectricTestRunner::class)
class PainterAttemptRearmTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val url = "https://images.example.org/duplicated.jpg"

    private fun successState(width: Int, height: Int): AsyncImagePainter.State.Success =
        AsyncImagePainter.State.Success(
            painter = ColorPainter(Color.Red),
            result = SuccessResult(
                image = ColorImage(0xFF1565C0.toInt(), width = width, height = height),
                request = ImageRequest.Builder(context).data(url).build(),
            ),
        )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a surviving loser reserves after an in-flight winner is disposed`() {
        val ledger = MediaAttemptLedger()
        val cache = DefaultIntrinsicMediaSizeCache()
        var showWinner by mutableStateOf(true)
        var showLoser by mutableStateOf(false)
        var winnerAttempt: PainterAttempt? = null
        var loserAttempt: PainterAttempt? = null

        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalIntrinsicMediaSizeCache provides cache,
                LocalMediaAttemptLedger provides ledger,
            ) {
                if (showWinner) {
                    key("A") { winnerAttempt = rememberPainterAttempt(url) }
                }
                if (showLoser) {
                    key("B") { loserAttempt = rememberPainterAttempt(url) }
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertTrue("A must win the only reservation", checkNotNull(winnerAttempt).renderPainter)
            showLoser = true
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertFalse(
                "B must lose while A holds the reservation",
                checkNotNull(loserAttempt).renderPainter,
            )
            // A holds a granted reservation and never received a terminal onState: disposing it
            // rolls the axis back to untried, in the SAME generation.
            showWinner = false
        }

        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle {
            assertTrue(
                "B must re-arm and take the reservation the disposed winner rolled back",
                checkNotNull(loserAttempt).renderPainter,
            )
            checkNotNull(loserAttempt).onState(successState(320, 240))
            assertTrue(
                "B must settle the painter after taking over",
                ledger.hasSucceeded(url, MediaAttemptKind.PAINTER),
            )
        }
    }
}
