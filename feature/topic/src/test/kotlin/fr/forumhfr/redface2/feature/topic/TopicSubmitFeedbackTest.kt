package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1301 — the single coordinator of the topic's post-submit snackbar. Replaces the historical
 * `TopicSubmittedElsewhereSnackbarTest`, whose free function launched the « page N » offer
 * independently of the « Message publié » confirmation — the double confirmation nicko reported on
 * the 0.61.0 beta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TopicSubmitFeedbackTest {

    @Test
    fun `the offer does not block the effect collector and keeps its action wiring`() = runTest {
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = {})
        val snackbarStarted = CompletableDeferred<Unit>()
        val snackbarGate = CompletableDeferred<SnackbarResult>()
        var returnedToCollector = false
        var opened = 0

        feedback.offerSubmittedElsewhere(
            scope = this,
            show = {
                assertTrue("the collector must resume before the snackbar suspends", returnedToCollector)
                snackbarStarted.complete(Unit)
                snackbarGate.await()
            },
            openPage = { opened += 1 },
        )
        returnedToCollector = true

        runCurrent()
        assertTrue(snackbarStarted.isCompleted)
        assertEquals(0, opened)

        snackbarGate.complete(SnackbarResult.ActionPerformed)
        advanceUntilIdle()

        assertEquals(1, opened)
    }

    @Test
    fun `a dismissed offer never opens the page`() = runTest {
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = {})
        var opened = 0

        feedback.offerSubmittedElsewhere(
            scope = this,
            show = { SnackbarResult.Dismissed },
            openPage = { opened += 1 },
        )
        advanceUntilIdle()

        assertEquals(0, opened)
    }

    @Test
    fun `the offer replaces the confirmation instead of queueing behind it`() = runTest {
        var dismissedCurrent = 0
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = { dismissedCurrent += 1 })
        val confirmationGate = CompletableDeferred<Unit>()
        var confirmationCancelled = false

        feedback.confirmSubmit(this) {
            try {
                confirmationGate.await()
            } finally {
                confirmationCancelled = true
            }
        }
        runCurrent()
        assertEquals(1, dismissedCurrent)

        feedback.offerSubmittedElsewhere(
            scope = this,
            show = { SnackbarResult.Dismissed },
            openPage = {},
        )
        advanceUntilIdle()

        assertTrue("the confirmation coroutine must be cancelled, not left in the mutex", confirmationCancelled)
        assertEquals("the host must be freed before the offer shows", 2, dismissedCurrent)
        assertFalse(confirmationGate.isCompleted)
    }

    @Test
    fun `the end of the refresh removes the confirmation`() = runTest {
        var dismissedCurrent = 0
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = { dismissedCurrent += 1 })
        var confirmationCancelled = false

        feedback.confirmSubmit(this) {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                confirmationCancelled = true
            }
        }
        runCurrent()
        assertFalse(confirmationCancelled)

        feedback.dismissConfirmation()
        advanceUntilIdle()

        assertTrue(confirmationCancelled)
        assertEquals("the host must be freed before the error replaces it", 2, dismissedCurrent)
    }

    @Test
    fun `the end of a refresh this coordinator did not confirm leaves the host alone`() = runTest {
        var dismissedCurrent = 0
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = { dismissedCurrent += 1 })

        // Another producer (moderation alert, favourite…) may own the host at that moment.
        feedback.dismissConfirmation()
        feedback.dismissConfirmation()

        assertEquals(0, dismissedCurrent)
    }

    @Test
    fun `the end of the refresh leaves an offer that already replaced the confirmation`() = runTest {
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = {})
        val offerGate = CompletableDeferred<SnackbarResult>()
        var offerEnded = false

        feedback.confirmSubmit(this) { CompletableDeferred<Unit>().await() }
        runCurrent()
        feedback.offerSubmittedElsewhere(
            scope = this,
            show = {
                try {
                    offerGate.await()
                } finally {
                    offerEnded = true
                }
            },
            openPage = {},
        )
        runCurrent()

        // The state change (refreshKind → None) can reach the screen AFTER the effect did.
        feedback.dismissConfirmation()
        runCurrent()

        assertFalse("the « page N » offer must survive the end of the refresh", offerEnded)

        offerGate.complete(SnackbarResult.Dismissed)
        advanceUntilIdle()
        assertTrue(offerEnded)
    }

    @Test
    fun `a new submit replaces the offer still on screen`() = runTest {
        val feedback = TopicSubmitFeedback(dismissCurrentSnackbar = {})
        var offerCancelled = false
        var confirmations = 0

        feedback.offerSubmittedElsewhere(
            scope = this,
            show = {
                try {
                    CompletableDeferred<SnackbarResult>().await()
                } finally {
                    offerCancelled = true
                }
            },
            openPage = {},
        )
        runCurrent()

        feedback.confirmSubmit(this) { confirmations += 1 }
        advanceUntilIdle()

        assertTrue(offerCancelled)
        assertEquals(1, confirmations)
    }
}
