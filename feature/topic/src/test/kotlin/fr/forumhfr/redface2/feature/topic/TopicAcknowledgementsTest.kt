package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TopicAcknowledgementsTest {
    @Test
    fun `fast refresh settles one acknowledgement independent of progress`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        assertNull(store.feedback.value)
        store.refreshed(id, lastPage = null)
        assertEquals(TopicAcknowledgement.Kind.Published, store.feedback.value?.kind)
        store.presented(id, 4_000)
        assertNull(store.feedback.value)
        store.refreshed(id, lastPage = 9)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS + 1)
        runCurrent()
        assertNull(store.feedback.value)
    }

    @Test
    fun `slow refresh settles at one second and late failure is only a warning`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Edit)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS - 1)
        runCurrent()
        assertNull(store.feedback.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TopicAcknowledgement.Kind.Edited, store.feedback.value?.kind)
        store.presented(id, 4_000)
        store.refreshFailed(id)
        assertEquals(TopicAcknowledgement.Kind.RefreshWarning, store.feedback.value?.kind)
    }

    @Test
    fun `failure before presentation replaces the pending success once`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        store.refreshFailed(id)
        assertTrue(store.feedback.value!!.refreshFailed)
        assertEquals(TopicAcknowledgement.Kind.Published, store.feedback.value?.kind)
        store.presented(id, 4_000)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(store.feedback.value)
    }

    @Test
    fun `restoration between acceptance and refresh keeps the identity and original wait bound`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Edit)
        advanceTimeBy(600)
        runCurrent()
        val restored = TopicAcknowledgements(copyHandle(saved), backgroundScope) { testScheduler.currentTime }
        restored.accountChanged("xaat")
        assertNull(restored.feedback.value)
        advanceTimeBy(399)
        runCurrent()
        assertNull(restored.feedback.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(id, restored.feedback.value?.id)
        assertEquals(TopicAcknowledgement.Kind.Edited, restored.feedback.value?.kind)
        restored.presented(id, 4_000)
        assertNull(restored.feedback.value)
    }

    @Test
    fun `ready success without an offer also survives death before presentation`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        store.refreshed(id, lastPage = null)
        val restoredHandle = copyHandle(saved)
        val restored = TopicAcknowledgements(restoredHandle, backgroundScope) { testScheduler.currentTime }
        assertEquals(id, restored.feedback.value?.id)
        restored.presented(id, 4_000)
        assertNull(TopicAcknowledgements(copyHandle(restoredHandle), backgroundScope).feedback.value)
    }

    @Test
    fun `failure queued before presentation survives restoration with the same success identity`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Edit)
        store.refreshFailed(id)
        val restored = TopicAcknowledgements(copyHandle(saved), backgroundScope) { testScheduler.currentTime }
        assertEquals(id, restored.feedback.value?.id)
        assertEquals(TopicAcknowledgement.Kind.Edited, restored.feedback.value?.kind)
        assertTrue(requireNotNull(restored.feedback.value).refreshFailed)
    }

    @Test
    fun `restoration after the original wait bound presents immediately without renewing it`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { 0L }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        val restored = TopicAcknowledgements(copyHandle(saved), backgroundScope) { 1_500L }
        runCurrent()
        assertEquals(id, restored.feedback.value?.id)
        assertEquals(TopicAcknowledgement.Kind.Published, restored.feedback.value?.kind)
    }

    @Test
    fun `saved offer retains its accessible deadline and can be taken only once`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        store.refreshed(id, lastPage = 6)
        store.presented(id, 30_000)
        advanceTimeBy(3_000)
        val restoredHandle = copyHandle(saved)
        val restored = TopicAcknowledgements(restoredHandle, backgroundScope) { testScheduler.currentTime }
        restored.accountChanged("xaat")
        assertEquals(30_000L, restored.feedback.value?.expiresAtMillis)
        restored.presented(id, 60_000)
        assertEquals(30_000L, restored.feedback.value?.expiresAtMillis)
        assertEquals(6, restored.takeOffer(id, "xaat"))
        assertNull(restored.takeOffer(id, "xaat"))
        assertNull(TopicAcknowledgements(copyHandle(restoredHandle), backgroundScope) { 4_000L }.feedback.value)
        assertNull(TopicAcknowledgements(copyHandle(saved), backgroundScope) { 31_000L }.feedback.value)
    }

    @Test
    fun `dismissal new submit departure and account switch invalidate an offer`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        fun offer(): Long = store.accept("xaat", PostSubmitOperation.Publish).also {
            store.refreshed(it, lastPage = 6)
            store.presented(it, 10_000)
        }
        val dismissed = offer()
        store.dismiss(dismissed)
        assertNull(store.takeOffer(dismissed, "xaat"))
        val replaced = offer()
        store.accept("xaat", PostSubmitOperation.Publish)
        assertNull(store.takeOffer(replaced, "xaat"))
        offer()
        store.clear()
        assertNull(store.feedback.value)
        val otherAccount = offer()
        store.accountChanged("other")
        assertNull(store.takeOffer(otherAccount, "xaat"))
        assertNull(store.feedback.value)
    }

    @Test
    fun `delete uses the same bounded success state`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        store.deleted("xaat")
        val ack = store.feedback.value!!
        assertEquals(TopicAcknowledgement.Kind.Deleted, ack.kind)
        assertFalse(ack.refreshFailed)
        assertNull(ack.lastPage)
    }

    @Test
    fun `deadline then refresh enriches the same acknowledgement only once`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val confirmation = requireNotNull(store.feedback.value)
        store.presented(id, 4_000)
        store.refreshed(id, lastPage = 6)
        val offer = requireNotNull(store.feedback.value)
        assertEquals(id, offer.id)
        assertEquals(6, offer.lastPage)
        store.refreshed(id, lastPage = 9)
        assertEquals(offer, store.feedback.value)
        store.dismiss(id, confirmation.revision)
        assertEquals(offer, store.feedback.value)
        store.presented(id, 60_000, confirmation.revision)
        assertEquals(offer, store.feedback.value)
        assertNull(store.feedback.value?.expiresAtMillis)
        store.presented(id, 10_000, offer.revision)
        val presentedOffer = store.feedback.value
        assertNull(store.takeOffer(id, "xaat", confirmation.revision))
        assertEquals(presentedOffer, store.feedback.value)
        assertEquals(6, store.takeOffer(id, "xaat", offer.revision))
        assertNull(store.takeOffer(id, "xaat", offer.revision))
    }

    @Test
    fun `terminal refresh without an offer cannot be enriched while queued`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        store.refreshed(id, lastPage = null)
        val terminal = store.feedback.value
        store.refreshed(id, lastPage = 6)
        assertEquals(terminal, store.feedback.value)
    }

    @Test
    fun `late edit never offers a page`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Edit)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 4_000)
        store.refreshed(id, lastPage = 6)
        assertNull(store.feedback.value)
    }

    @Test
    fun `clear account switch and new submit fence late refresh results`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        repeat(3) { invalidation ->
            val old = store.accept("xaat", PostSubmitOperation.Publish)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            runCurrent()
            when (invalidation) {
                0 -> store.clear()
                1 -> store.accountChanged("other")
                else -> store.accept("xaat", PostSubmitOperation.Publish)
            }
            store.refreshed(old, lastPage = 6)
            assertNull(store.feedback.value)
        }
    }

    @Test
    fun `refresh and deadline at the same instant offer the same page in either order`() = runTest {
        repeat(2) { deadlineFirst ->
            val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish)
            runCurrent()
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            if (deadlineFirst == 1) runCurrent()
            store.refreshed(id, lastPage = 6)
            runCurrent()
            assertEquals(6, store.feedback.value?.lastPage)
            store.presented(id, 10_000)
            assertEquals(6, store.takeOffer(id, "xaat"))
            assertNull(store.takeOffer(id, "xaat"))
        }
    }

    @Test
    fun `restored deadline keeps presentation consumption and permits only one late offer`() = runTest {
        repeat(2) { presented ->
            val saved = SavedStateHandle()
            val oldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val store = TopicAcknowledgements(saved, oldScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            runCurrent()
            if (presented == 1) store.presented(id, 4_000)
            val snapshot = copyHandle(saved)
            oldScope.cancel()
            val restored = TopicAcknowledgements(snapshot, backgroundScope) { testScheduler.currentTime }
            assertEquals(if (presented == 1) null else id, restored.feedback.value?.id)
            // No refresh is restarted on process death; a delivered result still has its identity.
            restored.refreshed(id, lastPage = 6)
            assertEquals(id, restored.feedback.value?.id)
            assertEquals(6, restored.feedback.value?.lastPage)
            restored.refreshed(id, lastPage = 9)
            assertEquals(6, restored.feedback.value?.lastPage)
        }
    }

    @Test
    fun `late offer survives process death with its revision and accessible deadline`() = runTest {
        val saved = SavedStateHandle()
        val oldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store = TopicAcknowledgements(saved, oldScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 4_000)
        advanceTimeBy(4_001)
        runCurrent()
        store.refreshed(id, lastPage = 6)
        assertEquals(6, store.feedback.value?.lastPage)
        store.presented(id, 30_000)
        val offer = store.feedback.value
        val snapshot = copyHandle(saved)
        oldScope.cancel()
        val restored = TopicAcknowledgements(snapshot, backgroundScope) { testScheduler.currentTime }
        assertEquals(offer, restored.feedback.value)
        restored.presented(id, 60_000)
        assertEquals(offer, restored.feedback.value)
        restored.refreshed(id, lastPage = 9)
        assertEquals(6, restored.takeOffer(id, "xaat"))
    }

    @Test
    fun `restored late offer does not repeat a confirmation already presented`() = runTest {
        repeat(2) { offerPresented ->
            val saved = SavedStateHandle()
            val oldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
            val store = TopicAcknowledgements(saved, oldScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            runCurrent()
            store.presented(id, 4_000)
            advanceTimeBy(1_000)
            runCurrent()
            store.refreshed(id, lastPage = 6)
            assertEquals(6, store.feedback.value?.lastPage)
            assertFalse(requireNotNull(store.feedback.value).offerOnly)
            if (offerPresented == 1) store.presented(id, 30_000)
            val offer = requireNotNull(store.feedback.value)
            advanceTimeBy(1_000)
            runCurrent()
            val snapshot = copyHandle(saved)
            oldScope.cancel()

            val restored = TopicAcknowledgements(snapshot, backgroundScope) { testScheduler.currentTime }
            assertEquals(offer.copy(offerOnly = true), restored.feedback.value)
            restored.presented(id, 60_000, offer.revision)
            assertEquals(
                offer.expiresAtMillis ?: (testScheduler.currentTime + 60_000),
                restored.feedback.value?.expiresAtMillis,
            )
            assertEquals(6, restored.takeOffer(id, "xaat", offer.revision))
            assertNull(restored.takeOffer(id, "xaat", offer.revision))
        }
    }

    @Test
    fun `predicted offer rides the single acknowledgement and a late refresh never presents again`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        assertNull(store.feedback.value)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val confirmation = requireNotNull(store.feedback.value)
        assertEquals(TopicAcknowledgement.Kind.Published, confirmation.kind)
        assertEquals(5, confirmation.lastPage)
        assertEquals(0, confirmation.revision)
        assertFalse(confirmation.offerOnly)
        store.presented(id, 10_000, confirmation.revision)
        val presented = requireNotNull(store.feedback.value)
        store.refreshed(id, lastPage = 5)
        assertEquals(presented, store.feedback.value)
        store.refreshed(id, lastPage = 9)
        assertEquals(presented, store.feedback.value)
        assertEquals(5, store.takeOffer(id, "xaat", confirmation.revision))
        assertNull(store.takeOffer(id, "xaat", confirmation.revision))
    }

    @Test
    fun `late refresh retargets a presented predicted offer in place and keeps its expiry`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 10_000)
        val presented = requireNotNull(store.feedback.value)
        advanceTimeBy(500)
        runCurrent()
        store.refreshed(id, lastPage = 6)
        assertEquals(presented.copy(lastPage = 6), store.feedback.value)
        assertEquals(9_500L, store.remainingMillis(id, presented.revision))
        advanceTimeBy(9_499)
        runCurrent()
        assertEquals(6, store.feedback.value?.lastPage)
        advanceTimeBy(1)
        runCurrent()
        assertNull(store.feedback.value)
        assertNull(store.takeOffer(id, "xaat"))
    }

    @Test
    fun `late refresh retargets a predicted offer still waiting for the host`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val queued = requireNotNull(store.feedback.value)
        store.refreshed(id, lastPage = 6)
        assertEquals(queued.copy(lastPage = 6), store.forPresentation(id, queued.revision))
        store.presented(id, 10_000, queued.revision)
        assertEquals(6, store.takeOffer(id, "xaat", queued.revision))
    }

    @Test
    fun `late refresh without a later page withdraws the presented predicted offer`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 10_000)
        val invalidation = store.invalidations.value
        store.refreshed(id, lastPage = null)
        assertNull(store.feedback.value)
        assertEquals(invalidation + 1, store.invalidations.value)
        assertNull(store.takeOffer(id, "xaat"))
        store.refreshed(id, lastPage = 9)
        assertNull(store.feedback.value)
    }

    @Test
    fun `late refresh can remove an unshown prediction while preserving the confirmation`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val queued = requireNotNull(store.feedback.value)
        store.refreshed(id, lastPage = null)
        assertEquals(queued.copy(lastPage = null), store.forPresentation(id, queued.revision))
        store.presented(id, 4_000, queued.revision)
        assertNull(store.feedback.value)
        assertNull(store.takeOffer(id, "xaat"))
    }

    @Test
    fun `fast refresh settles a predicted offer once with the refreshed pager`() = runTest {
        repeat(2) { stillOffered ->
            val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
            store.refreshed(id, lastPage = if (stillOffered == 1) 6 else null)
            val ack = requireNotNull(store.feedback.value)
            assertEquals(if (stillOffered == 1) 6 else null, ack.lastPage)
            assertEquals(0, ack.revision)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS + 1)
            runCurrent()
            assertEquals(ack, store.feedback.value)
        }
    }

    @Test
    fun `an edit ignores a predicted destination`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Edit, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val ack = requireNotNull(store.feedback.value)
        assertEquals(TopicAcknowledgement.Kind.Edited, ack.kind)
        assertNull(ack.lastPage)
        store.presented(id, 4_000)
        store.refreshed(id, lastPage = 6)
        assertNull(store.feedback.value)
    }

    @Test
    fun `failed refresh never keeps a predicted destination`() = runTest {
        repeat(2) { presented ->
            val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            runCurrent()
            if (presented == 1) store.presented(id, 10_000)
            store.refreshFailed(id)
            val failure = requireNotNull(store.feedback.value)
            assertNull(failure.lastPage)
            if (presented == 1) {
                assertEquals(TopicAcknowledgement.Kind.RefreshWarning, failure.kind)
            } else {
                assertEquals(id, failure.id)
                assertTrue(failure.refreshFailed)
            }
            assertNull(store.takeOffer(id, "xaat"))
        }
    }

    @Test
    fun `predicted offer presented at the deadline survives process death as an offer only`() = runTest {
        val saved = SavedStateHandle()
        val oldScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val store = TopicAcknowledgements(saved, oldScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 30_000)
        val offer = requireNotNull(store.feedback.value)
        val snapshot = copyHandle(saved)
        oldScope.cancel()

        val restored = TopicAcknowledgements(snapshot, backgroundScope) { testScheduler.currentTime }
        assertEquals(offer.copy(offerOnly = true), restored.feedback.value)
        restored.refreshed(id, lastPage = 6)
        assertEquals(offer.copy(offerOnly = true, lastPage = 6), restored.feedback.value)
        restored.presented(id, 60_000, offer.revision)
        assertEquals(offer.expiresAtMillis, restored.feedback.value?.expiresAtMillis)
        assertEquals(6, restored.takeOffer(id, "xaat", offer.revision))
    }

    @Test
    fun `expired predicted offer cannot be restored before or after its expiry callback`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        store.presented(id, 10_000)
        val beforeExpiry = copyHandle(saved)
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(store.feedback.value)
        // The refresh is still suspended: both snapshots retain the provisional acceptance.
        listOf(beforeExpiry, copyHandle(saved)).forEach { snapshot ->
            val restored = TopicAcknowledgements(snapshot, backgroundScope) { testScheduler.currentTime }
            assertNull(restored.feedback.value)
            assertNull(restored.takeOffer(id, "xaat"))
            restored.refreshed(id, lastPage = 6)
            assertNull(restored.feedback.value)
        }
    }

    @Test
    fun `dismissed or taken predicted offer stays consumed when restored during the refresh`() = runTest {
        repeat(2) { clicked ->
            val saved = SavedStateHandle()
            val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
            val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
            advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
            runCurrent()
            store.presented(id, 10_000)
            if (clicked == 1) {
                assertEquals(5, store.takeOffer(id, "xaat"))
            } else {
                store.dismiss(id)
            }
            val restored = TopicAcknowledgements(copyHandle(saved), backgroundScope) { testScheduler.currentTime }
            assertNull(restored.feedback.value)
            assertNull(restored.takeOffer(id, "xaat"))
            restored.refreshed(id, lastPage = 6)
            store.refreshed(id, lastPage = 6)
            assertNull(restored.feedback.value)
            assertNull(store.feedback.value)
        }
    }

    @Test
    fun `presented predicted offer supplies only navigation to subsequent hosts`() = runTest {
        val store = TopicAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(TOPIC_SUBMIT_ACK_WAIT_MILLIS)
        runCurrent()
        val first = requireNotNull(store.forPresentation(id))
        assertFalse(first.offerOnly)
        store.presented(id, 10_000, first.revision)
        advanceTimeBy(500)
        val next = requireNotNull(store.forPresentation(id, first.revision))
        assertTrue(next.offerOnly)
        assertEquals(first.id, next.id)
        store.presented(id, 30_000, next.revision)
        assertEquals(9_500L, store.remainingMillis(id))
        store.refreshed(id, lastPage = 3)
        assertEquals(next.copy(lastPage = 3), store.forPresentation(id, first.revision))
    }

    @Test
    fun `prediction saved while waiting is restored with the original wait bound`() = runTest {
        val saved = SavedStateHandle()
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        val id = store.accept("xaat", PostSubmitOperation.Publish, predictedLastPage = 5)
        advanceTimeBy(600)
        runCurrent()
        val restored = TopicAcknowledgements(copyHandle(saved), backgroundScope) { testScheduler.currentTime }
        assertNull(restored.feedback.value)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(id, restored.feedback.value?.id)
        assertEquals(5, restored.feedback.value?.lastPage)
        assertFalse(requireNotNull(restored.feedback.value).offerOnly)
    }

    @Test
    fun `legacy settled records remain terminal and retain their offer`() = runTest {
        val saved = SavedStateHandle(
            mapOf("topic_ack_offer" to arrayListOf(
                "7", "xaat", "6", "30000", "Published", "false", "1000", "true", "true",
            )),
        )
        val store = TopicAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        store.refreshed(7, lastPage = 9)
        assertEquals(6, store.takeOffer(7, "xaat"))
    }

    private fun copyHandle(source: SavedStateHandle) = SavedStateHandle(
        source.keys().associateWith { source.get<Any?>(it) },
    )
}
