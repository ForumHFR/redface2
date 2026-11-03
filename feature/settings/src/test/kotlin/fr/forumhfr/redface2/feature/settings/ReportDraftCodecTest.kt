package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportDraftCodecTest {
    @Test
    fun draftRoundTripPreservesEveryField() {
        ReportReason.entries.forEach { reason ->
            val draft = ReportDraft(reason, "Été\n[b]Texte[/b] : 😀", "https://example.org/?a=1&b=2#t3")
            val stored = ReportDraftCodec.encode(draft)
            assertTrue(stored.isPrivate)
            assertNull(stored.recipients)
            assertEquals(draft, ReportDraftCodec.decode(stored))
        }
        val empty = ReportDraft()
        assertEquals(empty, ReportDraftCodec.decode(ReportDraftCodec.encode(empty)))
    }

    @Test
    fun unknownVersionOrReasonDoesNotSilentlyBecomeAnotherReport() {
        assertNull(ReportDraftCodec.decode(EditorDraftStore.Draft("body", subject = "report:2:OTHER\n")))
        assertNull(ReportDraftCodec.decode(EditorDraftStore.Draft("body", subject = "report:1:MISSING\n")))
        assertNull(ReportDraftCodec.decode(EditorDraftStore.Draft("body")))
    }
}
