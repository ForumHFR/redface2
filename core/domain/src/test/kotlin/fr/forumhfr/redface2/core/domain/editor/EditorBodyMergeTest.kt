package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.model.write.QuoteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBodyMergeTest {
    private val scope = QuoteScope.Topic(23, 35421)
    private val q1 = "[quotemsg=101]one[/quotemsg]"
    private val q2 = "[quotemsg=202]two[/quotemsg]"

    @Test
    fun `restore orders D then quotes then typing made during GET`() {
        val body = "$q1\n\ntext typed during GET"
        val state = EditorBodyProvenance().delivered(scope, q1)
        assertEquals("D\n\n$q1\n\ntext typed during GET", mergeEditorBody("D", body, provenance = state).body)
    }

    @Test
    fun `only intact quotes of the same scope and content are deduplicated`() {
        val state = EditorBodyProvenance().delivered(scope, q1)
        assertEquals(q1, mergeEditorBody(q1, "$q1\n", provenance = state, scope = scope).body)
        val modified = q1.replace("one", "edited")
        assertEquals("$q1\n\n$modified", mergeEditorBody(q1, modified, provenance = state, scope = scope).body)
        assertEquals("$q1\n\n$q1", mergeEditorBody(q1, "$q1\n", provenance = state,
            scope = QuoteScope.Topic(24, 9), currentScope = scope).body)
    }

    @Test
    fun `multiple deliveries keep request order and deleted quotes are not replayed`() {
        val state = EditorBodyProvenance().delivered(scope, "$q1\n\n$q2")
        assertEquals("D\n\n$q1\n\n$q2\n\ntyped", mergeEditorBody("D", "$q1\n\n$q2\n\ntyped",
            provenance = state).body)
        assertEquals("D\n\n$q2\n\ntyped", mergeEditorBody("D", "$q2\n\ntyped", provenance = state).body)
        assertEquals("D\n\ntyped", mergeEditorBody("D", "typed", provenance = state).body)
    }

    @Test
    fun `MP uses the same merge and a modified block is retained even when D contains it`() {
        val mp = QuoteScope.PrivateMessage(42)
        val state = EditorBodyProvenance().delivered(mp, q1)
        val modified = q1.replace("one", "edited")
        assertEquals("draft\n$modified\n\n$modified", mergeEditorBody("draft\n$modified", modified,
            provenance = state).body)
        assertEquals("D\n\n$q1\n\ntyped", mergeEditorBody("D", "$q1\n\ntyped", provenance = state).body)
    }

    @Test
    fun `blank D and C equal D preserve the original buffer`() {
        assertEquals("live\n", mergeEditorBody(" ", "live\n").body)
        assertEquals("D", mergeEditorBody("D", "D").body)
    }

    @Test
    fun `every empty seed puts D before typing but an edited server seed keeps its rule`() {
        assertEquals("D\n\ntyped", mergeEditorBody("D", "typed").body)
        assertEquals("serXer\n\nD", mergeEditorBody("D", "serXer", seed = "server").body)
        assertEquals("D\n\ntyped", mergeEditorBody("D", "server\n\ntyped", seed = "server").body)
    }

    @Test
    fun `restore before delivery retains a boundary which survives edits and another delivery`() {
        val restored = mergeEditorBody("D", "typed")
        assertEquals("D\n\n$q1\n\ntyped", insertEditorQuotes(restored.body, q1, restored.provenance).body)
        val edited = restored.provenance.rebase(restored.body, "Draft\n\ntyped more")
        assertEquals("Draft\n\n$q1\n\ntyped more", insertEditorQuotes("Draft\n\ntyped more", q1, edited).body)
    }

    @Test
    fun `editing the middle of restored D moves both boundaries before the next quote`() {
        val restored = mergeEditorBody("draft old text", "$q1\n\ntyped",
            provenance = EditorBodyProvenance().delivered(scope, q1))
        val editedBody = restored.body.replace("old", "a longer replacement")
        val edited = restored.provenance.rebase(restored.body, editedBody)
        val draft = "draft a longer replacement text"
        assertEquals(draft.length, edited.draftEnd)
        assertEquals("$draft\n\n$q1".length, edited.quotesEnd)
        assertEquals("$draft\n\n$q1\n\n$q2\n\ntyped", insertEditorQuotes(editedBody, q2, edited).body)
    }

    @Test
    fun `deleting a separator keeps later delivery outside D and outside typing`() {
        val restored = mergeEditorBody("draft", "$q1\n\ntyped",
            provenance = EditorBodyProvenance().delivered(scope, q1))
        val bodies = listOf("draft$q1\n\ntyped", "draft\n\n${q1}typed")
        val expected = listOf("draft$q1\n\n$q2\n\ntyped", "draft\n\n$q1\n\n$q2\n\ntyped")
        bodies.zip(expected).forEach { (body, result) ->
            val edited = restored.provenance.rebase(restored.body, body)
            assertEquals("draft".length, edited.draftEnd)
            assertEquals(result, insertEditorQuotes(body, q2, edited).body)
        }
        val beforeGet = mergeEditorBody("draft", "typed")
        val withoutSeparator = "drafttyped"
        assertEquals("draft\n\n$q1\n\ntyped", insertEditorQuotes(withoutSeparator, q1,
            beforeGet.provenance.rebase(beforeGet.body, withoutSeparator)).body)
    }

    @Test
    fun `typing between two delivered blocks moves behind the complete batch on restore`() {
        val batch = "$q1\n\n$q2"
        val proof = EditorBodyProvenance().delivered(scope, batch)
        val body = "$q1\n\nbetween\n\n$q2\n\ntail"
        assertFalse(proof.isQuoteOnly(body))
        val restored = mergeEditorBody("D", body, provenance = proof)
        assertEquals("D\n\n$q1\n\n$q2\n\nbetween\n\ntail", restored.body)
        assertEquals("D".length, restored.provenance.draftEnd)
        assertEquals("D\n\n$batch".length, restored.provenance.quotesEnd)
        val q3 = "[quotemsg=303]three[/quotemsg]"
        assertEquals("D\n\n$batch\n\n$q3\n\nbetween\n\ntail",
            insertEditorQuotes(restored.body, q3, restored.provenance).body)
    }

    @Test
    fun `D and typed whitespace are preserved on restore and later delivery`() {
        val offered = "  D  \n"
        val typed = "  typed  \n"
        val restored = mergeEditorBody(offered, typed)
        assertEquals(offered + "\n\n" + typed, restored.body)
        assertEquals(offered + "\n\n" + q1 + "\n\n" + typed,
            insertEditorQuotes(restored.body, q1, restored.provenance).body)
        assertEquals("D\n\n$q1\n\n$typed", mergeEditorBody("D", "$q1\n\n$typed",
            provenance = EditorBodyProvenance().delivered(scope, q1)).body)
    }

    @Test
    fun `quote alone compares the complete last delivery ignoring trailing whitespace only`() {
        val state = EditorBodyProvenance().delivered(scope, "$q1\n\n$q2")
        assertTrue(state.isQuoteOnly("$q1\n\n$q2\n  "))
        listOf(q1, "$q2\n\n$q1", "$q1\n\n${q2.replace("two", "edited")}", "$q1\n\n$q2\nx").forEach {
            assertFalse(it, state.isQuoteOnly(it))
        }
        assertFalse(EditorBodyProvenance().isQuoteOnly(q1))
    }
}
