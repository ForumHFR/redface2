package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.editor.EditorDraftStore

/** Versioned metadata in the existing subject column; body remains the user's exact description. */
internal object ReportDraftCodec {
    private const val PREFIX = "report:1:"

    fun encode(draft: ReportDraft): EditorDraftStore.Draft = EditorDraftStore.Draft(
        body = draft.description,
        subject = "$PREFIX${draft.reason?.name.orEmpty()}\n${draft.link}",
        isPrivate = true,
    )

    fun decode(stored: EditorDraftStore.Draft): ReportDraft? {
        val metadata = stored.subject.orEmpty()
        if (!metadata.startsWith(PREFIX) || !metadata.contains('\n')) return null
        val reasonName = metadata.removePrefix(PREFIX).substringBefore('\n')
        val reason = ReportReason.entries.find { it.name == reasonName }
        return if (reasonName.isNotEmpty() && reason == null) {
            null
        } else {
            ReportDraft(reason, stored.body, metadata.substringAfter('\n'))
        }
    }
}
