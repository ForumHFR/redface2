package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.TextRange
import androidx.lifecycle.SavedStateHandle
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.Draft
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.DraftEntry
import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore.StoreWriteResult

/** ED-81/86: only content-free references and viewport metadata cross process death. */
internal class PostEditorRestoration(private val saved: SavedStateHandle) {
    val isRecreation = saved.contains(KEY)
    private val previousOwner = saved.get<String>(OWNER)
    private val previousKey = saved.get<String>(KEY)
    private val previousVersion = saved.get<String>(VERSION)
    private val previousStart = saved.get<Int>(START) ?: 0
    private val previousEnd = saved.get<Int>(END) ?: 0
    private val previousScroll = saved.get<Int>(SCROLL) ?: 0
    var scrollToRestore: Int? = null
        private set

    fun belongsTo(owner: String?, key: String?): Boolean =
        owner != null && owner == previousOwner && key == previousKey

    fun bind(owner: String?, key: String?) {
        val sameBinding = saved.get<String>(OWNER) == owner && saved.get<String>(KEY) == key
        if (!sameBinding) invalidate()
        saved[OWNER] = owner
        saved[KEY] = key.orEmpty()
    }

    fun matches(entry: DraftEntry, owner: String?, key: String?): Boolean =
        belongsTo(owner, key) && previousVersion == reference(entry.draft, entry.version)

    fun selection(length: Int): TextRange {
        scrollToRestore = previousScroll.coerceAtLeast(0)
        return TextRange(previousStart.coerceIn(0, length), previousEnd.coerceIn(0, length))
    }

    fun scrollRestored() { scrollToRestore = null }

    fun capture(draft: Draft, version: Long, selection: TextRange, scroll: Int) {
        saved[START] = selection.start
        saved[END] = selection.end
        saved[SCROLL] = scroll.coerceAtLeast(0)
        saved[VERSION] = reference(draft, version)
    }

    fun completed(draft: Draft, version: Long, result: StoreWriteResult?) {
        if (result !is StoreWriteResult.Written && saved.get<String>(VERSION) == reference(draft, version)) {
            saved.remove<String>(VERSION)
        }
    }

    fun invalidate() { saved.remove<String>(VERSION) }

    private fun reference(draft: Draft, version: Long): String =
        // A predicted numeric revision alone could also belong to a competing CAS writer.
        "$version:${Draft(body = draft.body).restoreOfferFingerprint()}"

    private companion object {
        const val OWNER = "postEditor.resumeOwner"
        const val KEY = "postEditor.resumeKey"
        const val VERSION = "postEditor.resumeVersion"
        const val START = "postEditor.selectionStart"
        const val END = "postEditor.selectionEnd"
        const val SCROLL = "postEditor.scroll"
    }
}
