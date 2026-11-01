package fr.forumhfr.redface2.core.domain.editor

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptOutcome
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.AttemptToken
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.BeginAttemptResult
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult

/** Compatibility for step-1 ViewModel fakes; step 3 must implement real versioned persistence. */
abstract class LegacyEditorDraftStore : EditorDraftStore {
    override suspend fun loadEntry(owner: String?, key: String): DraftEntry? = unsupported()
    override suspend fun saveIfVersion(
        owner: String?, key: String, draft: Draft, expectedVersion: Long,
    ): StoreWriteResult = unsupported()
    override suspend fun startNewComposition(
        owner: String?, key: String, draft: Draft, expectedVersion: Long,
    ): StoreWriteResult = unsupported()
    override suspend fun beginAttempt(
        owner: String?, key: String, snapshot: Draft, expectedVersion: Long,
    ): BeginAttemptResult = unsupported()
    override suspend fun finishAttempt(token: AttemptToken, outcome: AttemptOutcome): StoreWriteResult = unsupported()
    override suspend fun erasePublishedContent(token: AttemptToken): StoreWriteResult = unsupported()

    private fun unsupported(): Nothing = error("This legacy fake has no versioned draft contract yet")
}
