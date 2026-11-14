package fr.forumhfr.redface2.feature.messages

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateMessageEditAcknowledgementsTest {
    @Test
    fun `slow refresh cannot defer edit success beyond one second or replay it later`() = runTest {
        val ack = PrivateMessageEditAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        ack.accept("alice", 42L)
        runCurrent()
        advanceTimeBy(999)
        assertNull(ack.ready.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(42L, ack.ready.value)
        ack.presented(42L)
        ack.refreshed(42L)
        assertNull(ack.ready.value)
    }

    @Test
    fun `unpresented confirmation restores its existing deadline without restarting refresh`() = runTest {
        val saved = SavedStateHandle()
        val first = PrivateMessageEditAcknowledgements(saved, backgroundScope) { testScheduler.currentTime }
        first.accept("alice", 42L)
        runCurrent()
        advanceTimeBy(600)
        val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val restored = PrivateMessageEditAcknowledgements(restoredState, backgroundScope) { testScheduler.currentTime }
        restored.bind("alice")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(42L, restored.ready.value)
        restored.presented(42L)
        val presentedState = SavedStateHandle(restoredState.keys().associateWith { restoredState.get<Any?>(it) })
        val recreated = PrivateMessageEditAcknowledgements(presentedState, backgroundScope) { testScheduler.currentTime }
        recreated.bind("alice")
        runCurrent()
        assertNull(recreated.ready.value)
    }

    @Test
    fun `account switch and newer edit reject every old callback`() = runTest {
        val ack = PrivateMessageEditAcknowledgements(SavedStateHandle(), backgroundScope) { testScheduler.currentTime }
        ack.accept("alice", 42L)
        ack.accept("alice", 43L)
        ack.refreshed(42L)
        assertNull(ack.ready.value)
        ack.refreshed(43L)
        ack.presented(42L)
        assertEquals(43L, ack.ready.value)
        ack.bind("bob")
        ack.refreshed(43L)
        assertNull(ack.ready.value)
    }
}
