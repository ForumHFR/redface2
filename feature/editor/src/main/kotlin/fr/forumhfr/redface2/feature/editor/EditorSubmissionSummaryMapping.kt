package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.editor.EditorSubmissionSummaryKind

internal val SubmitError.summaryKind: EditorSubmissionSummaryKind?
    get() = when (this) {
        SubmitError.Uncertain -> EditorSubmissionSummaryKind.Uncertain
        SubmitError.DraftWriteFailed -> EditorSubmissionSummaryKind.DraftWriteFailed
        else -> null
    }
