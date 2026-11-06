package fr.forumhfr.redface2.core.domain.editor

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both orderings of the cancellation/network race must preserve the same durable classification. */
class EditorWriteOwnerTest {
    @Test
    fun `cancellation before the boundary seals every later emission`() {
        val owner = EditorWriteOwner("alice")
        assertTrue(owner.cancelBeforeEmission())
        assertTrue(owner.cancelBeforeEmission())
        assertThrows(IOException::class.java) { owner.markEmissionPossible() }
        assertThrows(IOException::class.java) { owner.emissionUnobserved() }
        assertTrue(owner.provesNoEmission)
    }

    @Test
    fun `crossing the boundary permanently prevents certain cancellation`() {
        val owner = EditorWriteOwner("alice")
        owner.markEmissionPossible()
        assertFalse(owner.cancelBeforeEmission())
        owner.markEmissionPossible()
        owner.emissionUnobserved()
        assertFalse(owner.provesNoEmission)
    }

    @Test
    fun `missing transport observation cannot become proof of non emission`() {
        val owner = EditorWriteOwner("alice")
        owner.emissionUnobserved()
        assertFalse(owner.provesNoEmission)
        assertFalse(owner.cancelBeforeEmission())
        owner.markEmissionPossible()
        assertFalse(owner.provesNoEmission)
    }
}
