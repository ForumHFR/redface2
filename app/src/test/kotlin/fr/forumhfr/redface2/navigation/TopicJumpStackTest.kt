package fr.forumhfr.redface2.navigation

import fr.forumhfr.redface2.feature.topic.TopicScrollAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #782 — pure contract of the quote-jump return stack: LIFO push/pop, the depth cap dropping the
 * OLDEST departure, single-stack-at-a-time across topics, and the null collapse that disables the
 * in-topic back interception once the chain is unwound.
 */
class TopicJumpStackTest {

    private fun entry(page: Int, index: Int = page * 10) =
        TopicJumpEntry(page = page, anchor = TopicScrollAnchor(index = index, offset = 5))

    @Test
    fun `x jumps then x pops unwind in LIFO order and collapse to null`() {
        var stack: TopicJumpStack? = null
        stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = 3))
        stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = 7))
        stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = 5))

        assertEquals(listOf(3, 7, 5), stack.entries.map { it.page })

        // Unwind: each back consumes the MOST RECENT departure first.
        assertEquals(entry(page = 5), stack.entries.last())
        stack = stack.popped()
        assertEquals(entry(page = 7), stack!!.entries.last())
        stack = stack.popped()
        assertEquals(entry(page = 3), stack!!.entries.last())
        // The emptied stack collapses to null so the BackHandler disables itself.
        assertNull(stack.popped())
    }

    @Test
    fun `the cap drops the oldest departure so back is never stuck past the cap`() {
        var stack: TopicJumpStack? = null
        repeat(TOPIC_JUMP_STACK_MAX + 3) { i ->
            stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = i + 1))
        }

        val pages = stack!!.entries.map { it.page }
        assertEquals(TOPIC_JUMP_STACK_MAX, pages.size)
        // Oldest (pages 1..3) dropped; the most recent departures are all retained in order.
        assertEquals((4..TOPIC_JUMP_STACK_MAX + 3).toList(), pages)
    }

    @Test
    fun `jumping in another topic resets the stack (one stack at a time)`() {
        var stack: TopicJumpStack? = null
        stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = 3))
        stack = stack.pushedJump(cat = 13, post = 999, entry = entry(page = 7))

        stack = stack.pushedJump(cat = 2, post = 111, entry = entry(page = 1))

        assertEquals(2, stack.cat)
        assertEquals(111, stack.post)
        assertEquals(listOf(1), stack.entries.map { it.page })
    }

    @Test
    fun `matches gates the interception to the stack's own topic`() {
        val empty: TopicJumpStack? = null
        val stack = empty.pushedJump(cat = 13, post = 999, entry = entry(page = 3))

        assertEquals(true, stack.matches(cat = 13, post = 999))
        assertEquals(false, stack.matches(cat = 13, post = 998))
        assertEquals(false, stack.matches(cat = 12, post = 999))
    }
}
