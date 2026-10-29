package fr.forumhfr.redface2.core.data.write

import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageFailure
import fr.forumhfr.redface2.core.domain.smiley.SmileyUsageMutationResult
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.FormBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuccessfulSmileyUsageRecorderTest {
    @Test
    fun `owner is captured normalized before sending and survives an account change`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        val owner = usage.recorder.captureOwner()
        usage.authState.value = AuthState.Authenticated("Bob")
        usage.recorder.record(owner, formBody(":o :o [:fictif b:3] [quote]:D[/quote]"), false)
        runCurrent()
        assertEquals(
            listOf(SmileyUsageRecorderFixture.Call("alice", mapOf(":o" to 2, "[:fictif b:3]" to 1))),
            usage.calls,
        )
    }

    @Test
    fun `owner convention does not trim and anonymous or blank owners cannot record`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        usage.authState.value = AuthState.Authenticated(" Alice ")
        assertEquals(" alice ", usage.recorder.captureOwner())
        listOf(AuthState.Anonymous, AuthState.Authenticated(" \t")).forEach { state ->
            usage.authState.value = state
            val owner = usage.recorder.captureOwner()
            assertNull(owner)
            usage.recorder.record(owner, formBody(":o"), false)
        }
        usage.recorder.record(" ", formBody(":o"), false)
        runCurrent()
        assertTrue(usage.calls.isEmpty())
    }

    @Test
    fun `edit records only positive occurrence deltas and ignores unchanged removed or quoted tokens`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        usage.recorder.record(
            "alice", formBody(":o :o :o [:fictif_a] [quote]:D[/quote] [:fictif b:3]"), false,
            initialContent = ":o :o [:fictif_a] :jap: [code][:fictif b:3][/code]",
        )
        runCurrent()
        assertEquals(
            listOf(SmileyUsageRecorderFixture.Call("alice", mapOf(":o" to 1, "[:fictif b:3]" to 1))),
            usage.calls,
        )
    }

    @Test
    fun `disabled smileys empty extraction and nonpositive edit delta never call storage`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        usage.recorder.record("alice", formBody(":o"), true)
        usage.recorder.record("alice", formBody("[fixed]:o[/fixed]"), false)
        usage.recorder.record("alice", formBody(":o"), false, initialContent = ":o :o")
        runCurrent()
        assertTrue(usage.calls.isEmpty())
    }

    @Test
    fun `local failure result and exceptions are swallowed without sensitive diagnostics`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        usage.storageResult = SmileyUsageMutationResult.Failure(SmileyUsageFailure.UNREADABLE_DOCUMENT)
        usage.recorder.record("alice", formBody("private body [:fictif_a]"), false)
        runCurrent()
        usage.storageError = IOException("private body [:fictif_a]")
        usage.recorder.record("alice", formBody("private body [:fictif_a]"), false)
        runCurrent()
        assertEquals(2, usage.calls.size)
        assertEquals(2, usage.diagnostics.entries.value.size)
        val messages = usage.diagnostics.entries.value.joinToString { it.message }
        assertFalse(messages.contains("private body"))
        assertFalse(messages.contains("[:fictif_a]"))
        usage.storageError = null
        usage.storageResult = SmileyUsageMutationResult.Updated
        usage.recorder.record("alice", formBody(":o"), false)
        runCurrent()
        assertEquals(3, usage.calls.size)
    }

    @Test
    fun `auth read failure degrades to no owner without exposing its message`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        usage.authError = IllegalStateException("private body [:fictif_a]")
        assertNull(usage.recorder.captureOwner())
        assertEquals("Local usage recording unavailable", usage.diagnostics.entries.value.single().message)
    }

    @Test
    fun `missing content form preserves the successful send result without recording`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        val success = ReplySubmitResult.Success(refreshUrl = null, targetPage = null)
        val sending = async {
            usage.recorder.record("alice", FormBody.Builder().add("sujet", ":o").build(), false)
            success
        }
        runCurrent()
        assertSame(success, sending.await())
        assertTrue(usage.calls.isEmpty())
        assertTrue(usage.diagnostics.entries.value.isEmpty())
    }

    @Test
    fun `record returns before storage completes and work survives sending coroutine cancellation`() = runTest {
        val usage = SmileyUsageRecorderFixture(backgroundScope)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        usage.beforeRecord = { started.complete(Unit); finish.await() }
        val sending = launch {
            usage.recorder.record("alice", formBody(":o"), false)
            returned.complete(Unit)
            // Simulate a screen which stays alive until the user closes it.
            CompletableDeferred<Unit>().await()
        }
        runCurrent()
        assertTrue(started.isCompleted)
        assertTrue("record must return while storage is still blocked", returned.isCompleted)
        assertFalse(finish.isCompleted)
        assertTrue(usage.calls.isEmpty())
        sending.cancelAndJoin()
        finish.complete(Unit)
        runCurrent()
        assertEquals(listOf(SmileyUsageRecorderFixture.Call("alice", mapOf(":o" to 1))), usage.calls)
    }

    private fun formBody(content: String): FormBody = FormBody.Builder().add("content_form", content).build()
}
