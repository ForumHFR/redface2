package fr.forumhfr.redface2.feature.editor

/** ED-169/172: all programmatic body/header paths share the same synchronous entry guard. */
internal val PostEditorIntent.mutatesEditor: Boolean
    get() = when (this) {
        is PostEditorIntent.SubmitClicked, is PostEditorIntent.SubmitConfirmed,
        PostEditorIntent.SubmitConfirmationDismissed, PostEditorIntent.CloseRequested,
        PostEditorIntent.ErrorDismissed, PostEditorIntent.UploadErrorDismissed,
        PostEditorIntent.TogglePreview -> false
        else -> true
    }

internal val TopicFormIntent.mutatesEditor: Boolean
    get() = when (this) {
        is TopicFormIntent.SubmitClicked, is TopicFormIntent.SubmitConfirmed,
        TopicFormIntent.SubmitConfirmationDismissed, TopicFormIntent.CloseRequested,
        TopicFormIntent.ErrorDismissed, TopicFormIntent.UploadErrorDismissed,
        TopicFormIntent.TogglePreview -> false
        else -> true
    }
