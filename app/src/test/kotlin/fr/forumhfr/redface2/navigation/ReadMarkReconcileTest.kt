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
    fun `withReadMark falls back to the MAX sentinel when no open-time date was captured`() {
        // #531 (Codex BLOCKER 2) — DT / deep-link open path never records an open date: the mark
        // falls back to Instant.MAX (NO_RECONCILE_BASELINE), NOT EPOCH. With EPOCH any server date
        // exceeded the baseline and a dot echo re-unread a just-read thread; with MAX no server date
        // is ever strictly after it, so the mark is never reconciled away (conservative).
        val marks = emptyMap<Int, Instant>().withReadMark(10, openDates = emptyMap())

        assertEquals(Instant.MAX, marks[10])
    }

    @Test
    fun `a mark with no open-time baseline (MAX sentinel) is never reconciled, even when shown unread`() {
        // #531 (Codex BLOCKER 2) — a thread read via the DT / deep-link path (no captured open date)
        // gets the MAX sentinel. No server date can be strictly after MAX, so even a fresh page-1
        // result still reporting it unread leaves the mark in place (it clears only on the auth purge).
        val marks = emptyMap<Int, Instant>().withReadMark(10, openDates = emptyMap())
        val fresh = listOf(conversation(threadId = 10, date = opened.plusSeconds(999), hasUnread = true))

        assertTrue("a MAX-baseline mark must survive any server date", reconcileReadMarks(marks, fresh).isEmpty())
        assertSame("a no-op reconciliation must not allocate", marks, marks.withoutReconciled(fresh))
    }

    @Test
    fun `reconcilePass ignores a generation already reconciled (idempotent re-fire)`() {
        // #531 (Codex BLOCKER 1) — the reconcile effect can re-fire for the SAME generation when the
        // screen re-enters composition (thread → back on a stale page-1 Content). The dedupe must
        // make the second pass a no-op : same marks, same high-water mark, regardless of content.
        val marks = mapOf(10 to opened)
        val realNewMp = listOf(conversation(threadId = 10, date = opened.plusSeconds(1), hasUnread = true))

        // First pass at generation 5 reconciles : the genuinely-newer mark is dropped, high-water → 5.
        val first = reconcilePass(marks, lastReconciled = 0, generation = 5, freshConversations = realNewMp)
        assertEquals(emptyMap<Int, Instant>(), first.marks)
        assertEquals(5, first.lastReconciled)

        // Re-fire at the SAME generation 5 : ignored. The mark map (now empty) is returned untouched,
        // and even feeding it back the original (still-marked) map must not re-reconcile it.
        val refire = reconcilePass(marks, lastReconciled = 5, generation = 5, freshConversations = realNewMp)
        assertSame("a re-fired generation must return the inputs untouched", marks, refire.marks)
        assertEquals(5, refire.lastReconciled)

        // A strictly newer generation (6) reconciles again.
        val next = reconcilePass(marks, lastReconciled = 5, generation = 6, freshConversations = realNewMp)
        assertEquals(emptyMap<Int, Instant>(), next.marks)
        assertEquals(6, next.lastReconciled)
    }

    @Test
    fun `reconcilePass reconciles at most once across a repeated generation`() {
        // #531 (Codex BLOCKER 1) — two reconcile calls for the same generation drop the mark exactly
        // once : the first removes it, the second (same generation) is a no-op, so the effect is
        // idempotent end-to-end. Mirrors the host loop : (marks, lastReconciled) fed back in.
        val realNewMp = listOf(conversation(threadId = 10, date = opened.plusSeconds(1), hasUnread = true))

        var marks = mapOf(10 to opened)
        var lastReconciled = 0

        repeat(3) {
            val pass = reconcilePass(marks, lastReconciled, generation = 7, freshConversations = realNewMp)
            marks = pass.marks
            lastReconciled = pass.lastReconciled
        }

        assertTrue("the mark is dropped once and stays dropped", marks.isEmpty())
        assertEquals(7, lastReconciled)
    }

    @Test
    fun `the open-time date is consumed on load so a re-open without a fresh date falls back to MAX`() {
        // #531 (Codex MAJOR 3) — the date captured at open-time is a PER-OPEN pending : onThreadLoaded
        // reads it to build the mark, then removes it from openThreadDates. A later re-open of the same
        // thread WITHOUT a fresh inbox date must therefore NOT reuse the old date — it falls back to the
        // MAX sentinel. This models the host's onThreadLoaded sequence with the pure helpers.

        // 1st open from the inbox : the row date is recorded as a pending open date.
        var openDates = mapOf(10 to opened)
        var marks = emptyMap<Int, Instant>()

        // onThreadLoaded #1 : build the mark from the captured date, THEN consume the date.
        marks = marks.withReadMark(10, openDates)
        openDates = openDates - 10
        assertEquals("first load keys the mark on the captured open date", opened, marks[10])
        assertTrue("the open date is consumed after load", openDates.isEmpty())

        // 2nd open of the SAME thread NOT from the inbox (deep-link / DT) : no fresh date captured.
        // onThreadLoaded #2 : with the pending consumed, the mark falls back to the MAX sentinel.
        marks = marks.withReadMark(10, openDates)
        assertEquals("a re-open without a fresh date must not reuse the stale date", Instant.MAX, marks[10])

        // And that MAX-baseline mark is never reconciled, even if the server still shows it unread.
        val fresh = listOf(conversation(threadId = 10, date = opened.plusSeconds(120), hasUnread = true))
        assertTrue("the re-keyed MAX mark must survive reconcile", reconcileReadMarks(marks, fresh).isEmpty())
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
