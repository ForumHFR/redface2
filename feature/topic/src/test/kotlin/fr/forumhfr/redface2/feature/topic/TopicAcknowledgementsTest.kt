package fr.forumhfr.redface2.feature.topic

import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun copyHandle(source: SavedStateHandle) = SavedStateHandle(
        source.keys().associateWith { source.get<Any?>(it) },
    )
}
