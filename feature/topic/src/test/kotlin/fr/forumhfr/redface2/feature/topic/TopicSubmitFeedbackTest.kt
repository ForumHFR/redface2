package fr.forumhfr.redface2.feature.topic

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicSubmitFeedbackTest {
    @Test
    fun `successes keep the current display then present only the latest success`() = runTest {
        val feedback = TopicSubmitFeedback()
        val gate = CompletableDeferred<Unit>()
        val shown = mutableListOf<Int>()
        feedback.present(this) { shown += 1; gate.await() }
        runCurrent()
        feedback.present(this) { shown += 2 }
        feedback.present(this) { shown += 3 }
        runCurrent()
        assertEquals(listOf(1), shown)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1, 3), shown)
    }

    @Test
    fun `a refresh warning can interrupt a success without replaying it`() = runTest {
        val feedback = TopicSubmitFeedback()
        val shown = mutableListOf<Int>()
        feedback.present(this) { shown += 1; CompletableDeferred<Unit>().await() }
        runCurrent()
        feedback.present(this, error = true) { shown += 2 }
        runCurrent()
        assertEquals(listOf(1, 2), shown)
    }
}
