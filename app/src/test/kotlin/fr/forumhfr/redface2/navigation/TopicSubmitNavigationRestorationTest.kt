package fr.forumhfr.redface2.navigation

import androidx.compose.runtime.saveable.SaverScope
import fr.forumhfr.redface2.core.model.write.PostSubmitOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicSubmitNavigationRestorationTest {
    @Test
    fun `unconsumed result survives save restore with operation account and unique identity`() {
        val relay = TopicSubmitRelay()
        relay.publish(25, 1234, 2, 640, listOf(640), PostSubmitOperation.Edit, "xaat")
        val saved = with(TopicSubmitRelay.Saver) {
            object : SaverScope {
                override fun canBeSaved(value: Any) = true
            }.save(relay)
        }!!
        val restored = TopicSubmitRelay.Saver.restore(saved)!!
        val pending = restored.pending!!
        assertEquals("xaat", pending.result.account)
        assertEquals(PostSubmitOperation.Edit, pending.result.operation)
        assertEquals(listOf(640), pending.result.quotedNumreponses)
        restored.consume(pending.result.eventId + 1)
        assertEquals(pending, restored.pending)
        restored.consume(pending.result.eventId)
        assertNull(restored.pending)
        restored.publish(25, 1234, 2, null, emptyList(), PostSubmitOperation.Publish, "xaat")
        assertEquals(pending.result.eventId + 1, restored.pending!!.result.eventId)
    }

    @Test
    fun `session purge clears the relay without reusing its event identity`() {
        val relay = TopicSubmitRelay()
        relay.publish(25, 1234, 2, null, emptyList(), PostSubmitOperation.Publish, "xaat")
        val previous = relay.pending!!.result.eventId
        relay.clear()
        val restored = TopicSubmitRelay.restore(relay.save())
        assertNull(restored.pending)
        restored.publish(25, 1234, 2, null, emptyList(), PostSubmitOperation.Edit, "other")
        assertEquals(previous + 1, restored.pending!!.result.eventId)
    }
}
