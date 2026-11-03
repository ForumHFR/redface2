package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Attempt
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptStatus
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
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
            assertEquals(draft, ReportDraftCodec.decode(DraftEntry(stored, 0)))
            assertEquals(draft, ReportDraftCodec.decode(attemptEntry(stored)))
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

    @Test
    fun purgedUnresolvedAttemptsRestoreAnEmptyReport() {
        val stored = Draft("", isPrivate = true, updatedAt = 1234)
        for (status in listOf(AttemptStatus.IN_PROGRESS, AttemptStatus.UNCERTAIN)) {
            assertEquals(ReportDraft(), ReportDraftCodec.decode(attemptEntry(stored, status)))
        }
    }

    @Test
    fun purgedDraftAfterCertainFailureRestoresAnEmptyReport() {
        val entry = DraftEntry(Draft("", isPrivate = true, updatedAt = 1234), version = 7)
        assertEquals(ReportDraft(), ReportDraftCodec.decode(entry))
        assertTrue(ReportDraftCodec.isPurged(entry))
    }

    @Test
    fun missingMetadataIsAcceptedOnlyForErasedPrivateRecoverableEntries() {
        val stored = Draft("", isPrivate = true)
        assertNull(ReportDraftCodec.decode(stored))
        assertNull(ReportDraftCodec.decode(attemptEntry(stored, AttemptStatus.PUBLISHED)))
        val malformed = listOf(
            stored.copy(isPrivate = false),
            stored.copy(body = " "),
            stored.copy(body = "Private content"),
            stored.copy(subject = ""),
            stored.copy(recipients = ""),
            stored.copy(subject = "Private subject"),
            stored.copy(recipients = "recipient"),
            stored.copy(subject = "report:2:OTHER\n"),
            stored.copy(subject = "report:1:MISSING\n"),
        )
        malformed.forEach {
            assertNull(ReportDraftCodec.decode(DraftEntry(it, version = 7)))
            assertNull(ReportDraftCodec.decode(attemptEntry(it)))
        }
    }

    private fun attemptEntry(draft: Draft, status: AttemptStatus = AttemptStatus.UNCERTAIN) = DraftEntry(
        draft, version = 7,
        attempt = Attempt(AttemptToken("tester", "report", "attempt", version = 3), status),
    )
}
