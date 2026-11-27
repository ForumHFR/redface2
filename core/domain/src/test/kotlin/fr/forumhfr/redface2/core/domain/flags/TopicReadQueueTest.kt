package fr.forumhfr.redface2.core.domain.flags

import fr.forumhfr.redface2.core.model.FlagType
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicReadQueueTest {
    @Test
    fun `acknowledgement is scoped to the account the type and the version of the read`() {
        val queue = TopicReadQueue()
        val read = TopicRead(23, 35395)
        val older = TopicRead(13, 100217)
        val all = FlagType.entries.toSet()
        queue.record("Alice", older)
        queue.record("Alice", read)
        queue.record("Bob", read)
        val checkpoint = queue.checkpoint()
        queue.record("Alice", read)

        queue.acknowledge("ALICE", checkpoint, mapOf(read to all, older to setOf(FlagType.CYAN)))

        assertEquals(mapOf(older to (all - FlagType.CYAN)), queue.pending("alice", checkpoint))
        assertEquals(mapOf(older to (all - FlagType.CYAN), read to all), queue.pending("alice", queue.checkpoint()))
        assertEquals(mapOf(read to all), queue.pending("bob", checkpoint))
    }
}
