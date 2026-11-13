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

    @Test fun `delivery filter preserves whole batch separators for the durable quote-only fingerprint`() {
        val batch = "\n$q1\n\n$q2\n"
        assertEquals(FilteredEditorQuotes(batch, 0), filterEditorQuotes(batch, "typing"))
        assertEquals(FilteredEditorQuotes(q2, 1), filterEditorQuotes(batch, q1))
        assertEquals(FilteredEditorQuotes(batch, 0), filterEditorQuotes(batch, q1.replace("one", "edited")))
    }

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
    fun `typing appended after a restore into an empty field stays behind a later quote - ED89`() {
        val restored = mergeEditorBody("D", "")
        assertEquals("D", restored.body)
        val typed = "D\n\ntexte"
        val rebased = restored.provenance.rebase(restored.body, typed)
        assertEquals("D".length, rebased.draftEnd)
        assertEquals("D".length, rebased.quotesEnd)
        assertEquals("D\n\n$q1\n\ntexte", insertEditorQuotes(typed, q1, rebased).body)
        // Same boundary rule after a delivered quote: later typing never moves the next quote behind it.
        val delivered = insertEditorQuotes(typed, q1, rebased)
        val more = delivered.body + " encore"
        assertEquals("D\n\n$q1\n\n$q2\n\ntexte encore",
            insertEditorQuotes(more, q2, delivered.provenance.rebase(delivered.body, more)).body)
    }

    @Test
    fun `an insertion exactly at the boundary is typing with or without text after it - ED89`() {
        // Without a suffix: appended to a field holding only the restored D.
        val alone = mergeEditorBody("D", "")
        val appended = alone.provenance.rebase(alone.body, "D more")
        assertEquals("D".length, appended.draftEnd)
        assertEquals("D".length, appended.quotesEnd)
        // With an intact suffix: the same insertion in front of older typing follows the same rule.
        val restored = mergeEditorBody("D", "typed")
        val inserted = "D more\n\ntyped"
        val rebased = restored.provenance.rebase(restored.body, inserted)
        assertEquals("D".length, rebased.draftEnd)
        assertEquals("D".length, rebased.quotesEnd)
        val delivered = insertEditorQuotes(inserted, q1, rebased).body
        assertTrue(delivered.startsWith("D\n\n$q1"))
        assertTrue(delivered.endsWith("typed"))
        // After delivered quotes too: typing at the end of the quotes stays behind the next quote.
        val quoted = insertEditorQuotes(restored.body, q1, restored.provenance)
        val typedAtQuotesEnd = quoted.body.replace("$q1\n\ntyped", "$q1 aside\n\ntyped")
        val moved = quoted.provenance.rebase(quoted.body, typedAtQuotesEnd)
        assertEquals("D\n\n$q1".length, moved.quotesEnd)
        assertTrue(insertEditorQuotes(typedAtQuotesEnd, q2, moved).body.startsWith("D\n\n$q1\n\n$q2"))
    }

    @Test
    fun `an offer identical to the field is hidden without hiding or deleting the others - ED121`() {
        val same = EditorDraftStore.DraftOffer("a", 1, EditorDraftStore.Draft("same", updatedAt = 2))
        val other = EditorDraftStore.DraftOffer("b", 1, EditorDraftStore.Draft("other", updatedAt = 1))
        val offers = listOf(same, other)
        val none = emptyList<EditorDraftStore.DraftOffer>()
        assertEquals(listOf(other), offers.visibleOver("same"))
        // The field differs again: the hidden offer is back, it was never removed.
        assertEquals(offers, offers.visibleOver("same, edited"))
        assertEquals(listOf(other) to "other", narrowDraftOffers(offers, "same", "same"))
        assertEquals(none to null, narrowDraftOffers(listOf(same), "same", "same"))
        // A decision in flight (nothing shown) is never shown again by a hydration.
        assertEquals(listOf(other) to null, narrowDraftOffers(offers, null, "same"))
        // Single legacy offer without a list.
        assertEquals(none to "kept", narrowDraftOffers(emptyList(), "kept", "typed"))
        assertEquals(none to null, narrowDraftOffers(emptyList(), "kept", "kept"))
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
    fun `coalesced edits to D and typing never move quotes before D`() {
        val longQuote = "[quotemsg=101]" + "one ".repeat(30) + "[/quotemsg]"
        val restored = mergeEditorBody("draft", "$longQuote\n\ntyped",
            provenance = EditorBodyProvenance().delivered(scope, longQuote))
        val current = restored.body.replace("draft", "draft expanded").replace("typed", "rewritten tail")
        val rebased = restored.provenance.rebase(restored.body, current)
        assertEquals("draft expanded".length, rebased.draftEnd)
        assertTrue(rebased.quotesEnd >= rebased.draftEnd)
        assertTrue(rebased.quotesEnd <= current.length)
        val inserted = insertEditorQuotes(current, q2, rebased)
        assertTrue(inserted.body.startsWith("draft expanded\n\n$q2"))
    }

    @Test
    fun `delivery evidence survives deleted blocks and compacts only repeated exact requests`() {
        val modified = q1.replace("one", "edited")
        val q3 = "[quotemsg=303]three[/quotemsg]"
        val q4 = "[quotemsg=404]four[/quotemsg]"
        var proof = EditorBodyProvenance().delivered(scope, q1)
        proof = proof.delivered(scope, q2, "$q1\n\n$q2\n\ntyped")
        proof = proof.delivered(scope, q3, "$modified\n\n$q3\n\ntyped")
        assertEquals(listOf(q1, q2, q3), proof.deliveries.map { it.body })
        assertEquals("D\n\n$modified\n\n$q3\n\ntyped",
            mergeEditorBody("D", "$modified\n\n$q3\n\ntyped", provenance = proof).body)
        // ED-89: removed blocks may return. Repeating an exact request does not grow its proof.
        repeat(100) { proof = proof.delivered(scope, q4, q4) }
        assertEquals(listOf(q1, q2, q3, q4), proof.deliveries.map { it.body })
        proof = proof.rebase(q4, "typed only")
        assertEquals(listOf(q1, q2, q3, q4), proof.deliveries.map { it.body })
        assertTrue(proof.isQuoteOnly(q4 + "\n  "))
        assertFalse(proof.isQuoteOnly(q3))
    }

    @Test
    fun `compaction keeps distinct intact contents of the same scoped post for ED-89`() {
        val revised = q1.replace("one", "revised")
        val current = "$q1\n\n$revised\n\n$q2\n\ntyped"
        val proof = EditorBodyProvenance().delivered(scope, q1)
            .delivered(scope, revised, current).delivered(scope, q2, current).retainFor(current)
        assertEquals(listOf(q1, revised, q2), proof.deliveries.map { it.body })
        assertEquals(current, mergeEditorBody(q1, current, provenance = proof).body)
    }

    @Test
    fun `compaction retains the whole last batch and scopes older evidence by category`() {
        val otherScope = QuoteScope.Topic(24, 1)
        val batch = "$q1\n\n$q2"
        val proof = EditorBodyProvenance().delivered(otherScope, q1)
            .delivered(scope, batch, batch).retainFor(batch)
        assertEquals(batch, proof.deliveries.last().body)
        assertTrue(proof.isQuoteOnly(batch + "\n"))
        assertEquals(otherScope, proof.deliveries.first().scope)
        assertEquals("D\n\n$batch\n\ntyped", mergeEditorBody("D", "$batch\n\ntyped",
            provenance = proof, scope = scope, currentScope = scope).body)
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
