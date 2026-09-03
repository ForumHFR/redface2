package fr.forumhfr.redface2.feature.topic

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicSubmittedElsewhereSnackbarTest {

    @Test
    fun `submitted-elsewhere snackbar does not block the effect collector and keeps action wiring`() = runTest {
        val anchor = TopicScrollAnchor(index = 3, offset = 12)
        val snackbarStarted = CompletableDeferred<Unit>()
        val snackbarGate = CompletableDeferred<SnackbarResult>()
        var returnedToCollector = false
        var opened: OpenCall? = null

        launchPostSubmittedElsewhereSnackbar(
            effect = TopicEffect.PostSubmittedElsewhere(page = 5, scrollTo = 777),
            message = "Message publie page 5",
            actionLabel = "Y aller",
            showSnackbar = { message, actionLabel, duration ->
                assertTrue("the collector must resume before the snackbar suspends", returnedToCollector)
                assertEquals("Message publie page 5", message)
                assertEquals("Y aller", actionLabel)
                assertEquals(SnackbarDuration.Long, duration)
                snackbarStarted.complete(Unit)
                snackbarGate.await()
            },
            departureAnchor = { anchor },
            openSubmittedPostPage = { page, scrollTo, departureAnchor ->
                opened = OpenCall(page, scrollTo, departureAnchor)
            },
        )
        returnedToCollector = true

        runCurrent()
        assertTrue(snackbarStarted.isCompleted)
        assertNull(opened)

        snackbarGate.complete(SnackbarResult.ActionPerformed)
        advanceUntilIdle()

        assertEquals(OpenCall(page = 5, scrollTo = 777, departureAnchor = anchor), opened)
    }

    private data class OpenCall(
        val page: Int,
        val scrollTo: Int?,
        val departureAnchor: TopicScrollAnchor?,
    )
}
