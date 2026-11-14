package fr.forumhfr.redface2.core.data.editor

import fr.forumhfr.redface2.core.domain.editor.EditorBodyProvenance
import fr.forumhfr.redface2.core.domain.editor.mergeEditorBody
import fr.forumhfr.redface2.core.model.write.QuoteScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorBodyProvenanceCodecTest {
    @Test fun `invalid JSON boundaries version and scope decode to absent evidence`() {
        val invalid = listOf(
            "not JSON",
            """{"version":1,"draftEnd":21,"quotesEnd":4,"deliveries":[]}""",
            """{"version":1,"draftEnd":-1,"quotesEnd":0,"deliveries":[]}""",
            """{"version":99,"draftEnd":0,"quotesEnd":0,"deliveries":[]}""",
            """{"draftEnd":0,"quotesEnd":0,"deliveries":[{"type":"unknown","cat":23,"post":42,"body":"Q"}]}""",
        )
        invalid.forEach { assertNull(EditorBodyProvenanceCodec.decode(it)) }
    }

    @Test fun `rebased boundaries round trip after coalesced draft and typing edits`() {
        val quote = "[quotemsg=101]" + "one ".repeat(30) + "[/quotemsg]"
        val scope = QuoteScope.Topic(23, 35421)
        val restored = mergeEditorBody("draft", "$quote\n\ntyped",
            provenance = EditorBodyProvenance().delivered(scope, quote))
        val current = restored.body.replace("draft", "draft expanded").replace("typed", "rewritten tail")
        val rebased = restored.provenance.rebase(restored.body, current)
        assertEquals(rebased, EditorBodyProvenanceCodec.decode(EditorBodyProvenanceCodec.encode(rebased)))
    }
}
