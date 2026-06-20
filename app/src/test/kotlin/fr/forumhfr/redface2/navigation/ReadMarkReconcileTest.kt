package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.core.model.messages.PrivateMessageSummary
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #531 — covers the [reconcileReadMarks] pure helper that reconciles the optimistic inbox read marks
 * (threadId → date seen at open-time) against a fresh page-1 network result. Compose-free, like the
 * other nav-state helper tests ([TopicScrollCacheTest], [TopicTitleCacheTest]).
 *
 * Invariant: a mark is dropped ONLY when the server still reports the thread unread AND its server
 * date is STRICTLY after the recorded open-time date (a genuine new MP). An identical date is the echo
 * of the pre-read dot and must keep the mark, or a just-read thread would re-blink unread.
 */
class ReadMarkReconcileTest {

    private val opened = Instant.parse("2026-06-18T10:00:00Z")

    private fun conversation(
        threadId: Int,
        date: Instant,
        hasUnread: Boolean,
    ) = PrivateMessageSummary(
        threadId = threadId,
        correspondent = "Corr$threadId",
        subject = "Sujet $threadId",
        date = date,
        hasUnread = hasUnread,
    )

    @Test
    fun `keeps the mark when the server date equals the open-time date (echo of the pre-read dot)`() {
        val marks = mapOf(10 to opened)
        val fresh = listOf(conversation(threadId = 10, date = opened, hasUnread = true))

        assertTrue("same-date unread is an echo, mark must survive", reconcileReadMarks(marks, fresh).isEmpty())
    }

    @Test
    fun `drops the mark when the server date is strictly after the open-time date (real new MP)`() {
        val marks = mapOf(10 to opened)
        val fresh = listOf(conversation(threadId = 10, date = opened.plusSeconds(1), hasUnread = true))

        assertEquals(setOf(10), reconcileReadMarks(marks, fresh))
    }

    @Test
    fun `does not drop a mark for a conversation absent from page 1`() {
        val marks = mapOf(10 to opened, 20 to opened)
        // Only thread 10 is on the fresh page, and it is unchanged (read). Thread 20 is absent: no
        // page-1 entry means no signal, so its mark must NOT be inferred stale.
        val fresh = listOf(conversation(threadId = 10, date = opened, hasUnread = false))

        assertTrue(reconcileReadMarks(marks, fresh).isEmpty())
    }

    @Test
    fun `leaves the mark untouched when the server reports the thread read`() {
        val marks = mapOf(10 to opened)
        // hasUnread = false : the server agrees the thread is read, even with a newer date — there is
        // nothing to reconcile (the read override is already consistent with the server).
        val fresh = listOf(conversation(threadId = 10, date = opened.plusSeconds(60), hasUnread = false))

        assertTrue(reconcileReadMarks(marks, fresh).isEmpty())
    }

    @Test
    fun `ignores a fresh unread conversation that was never marked read`() {
        // No mark for thread 30 : a normally-unread inbox row is not our concern (the list already
        // shows it unread). reconcileReadMarks only ever removes EXISTING marks.
        val marks = mapOf(10 to opened)
        val fresh = listOf(conversation(threadId = 30, date = opened.plusSeconds(99), hasUnread = true))

        assertTrue(reconcileReadMarks(marks, fresh).isEmpty())
    }

    @Test
    fun `comparison is strict (after), not greater-or-equal`() {
        // Belt-and-braces twin of the echo case: the boundary (exactly equal) must NOT drop. A single
        // nanosecond later must drop. Guards against a future >= regression.
        val marks = mapOf(10 to opened)
        val equalDate = listOf(conversation(threadId = 10, date = opened, hasUnread = true))
        val oneNanoLater = listOf(conversation(threadId = 10, date = opened.plusNanos(1), hasUnread = true))

        assertTrue("exactly equal must keep the mark", reconcileReadMarks(marks, equalDate).isEmpty())
        assertEquals("one nanosecond later must drop", setOf(10), reconcileReadMarks(marks, oneNanoLater))
    }

    @Test
    fun `withoutReconciled returns the same instance when nothing is stale (no recomposition)`() {
        val marks = mapOf(10 to opened)
        val fresh = listOf(conversation(threadId = 10, date = opened, hasUnread = true)) // echo → keep

        assertSame("a no-op reconciliation must not allocate a new map", marks, marks.withoutReconciled(fresh))
    }

    @Test
    fun `withoutReconciled removes the genuinely-newer mark`() {
        val marks = mapOf(10 to opened, 20 to opened)
        val fresh = listOf(
            conversation(threadId = 10, date = opened.plusSeconds(1), hasUnread = true), // real new MP → drop
            conversation(threadId = 20, date = opened, hasUnread = true), // echo → keep
        )

        assertEquals(mapOf(20 to opened), marks.withoutReconciled(fresh))
    }

    @Test
    fun `withReadMark records the captured open-time date`() {
        val marks = emptyMap<Int, Instant>().withReadMark(10, openDates = mapOf(10 to opened))

        assertEquals(opened, marks[10])
    }

    @Test
    fun `withReadMark falls back to EPOCH when no open-time date was captured`() {
        // DT / deep-link open path never records an open date: the mark falls back to EPOCH so any
        // later server date strictly exceeds it and can reconcile the thread back to unread.
        val marks = emptyMap<Int, Instant>().withReadMark(10, openDates = emptyMap())

        assertEquals(Instant.EPOCH, marks[10])
    }

    @Test
    fun `drops only the genuinely-newer marks in a mixed page`() {
        val marks = mapOf(
            10 to opened, // echo (same date) → keep
            20 to opened, // real new MP (later) → drop
            30 to opened, // server reports read → keep
        )
        val fresh = listOf(
            conversation(threadId = 10, date = opened, hasUnread = true),
            conversation(threadId = 20, date = opened.plusSeconds(5), hasUnread = true),
            conversation(threadId = 30, date = opened.plusSeconds(5), hasUnread = false),
            conversation(threadId = 40, date = opened.plusSeconds(5), hasUnread = true), // unmarked → ignore
        )

        assertEquals(setOf(20), reconcileReadMarks(marks, fresh))
    }
}
