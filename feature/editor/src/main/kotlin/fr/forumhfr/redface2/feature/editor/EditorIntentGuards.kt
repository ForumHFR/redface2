package fr.forumhfr.redface2.feature.editor

/** ED-169/172: all programmatic body/header paths share the same synchronous entry guard. */
internal val PostEditorIntent.mutatesEditor: Boolean
    get() = when (this) {
        is PostEditorIntent.SubmitClicked, is PostEditorIntent.SubmitConfirmed,
        PostEditorIntent.SubmitConfirmationDismissed, PostEditorIntent.CloseRequested,
        PostEditorIntent.UnsavedCloseDismissed, PostEditorIntent.UnsavedCloseConfirmed,
        PostEditorIntent.ErrorDismissed, PostEditorIntent.ReloadRequested, PostEditorIntent.PendingQuoteIgnored,
        PostEditorIntent.PendingQuoteResumed, PostEditorIntent.LegacyQuoteNoticeDismissed,
        PostEditorIntent.UploadErrorDismissed, PostEditorIntent.DraftWriteFailureDismissed,
        PostEditorIntent.TogglePreview, PostEditorIntent.Started, PostEditorIntent.Stopped -> false
        else -> true
    }

/** Upload callbacks alone may insert a body while an upload is in flight. */
internal val PostEditorIntent.mutatesBody: Boolean
    get() = when (this) {
        is PostEditorIntent.ToolbarActionClicked, is PostEditorIntent.SmileySelected,
        is PostEditorIntent.ImageUrlInserted, is PostEditorIntent.ImagePicked,
        is PostEditorIntent.ImagesPicked, PostEditorIntent.DraftRestoreRequested,
        is PostEditorIntent.DraftOfferRestored -> true
        else -> false
    }

internal val TopicFormIntent.mutatesEditor: Boolean
    get() = when (this) {
        TopicFormIntent.Started, TopicFormIntent.Stopped -> false
        is TopicFormIntent.SubmitClicked, is TopicFormIntent.SubmitConfirmed,
        TopicFormIntent.SubmitConfirmationDismissed, TopicFormIntent.CloseRequested,
        TopicFormIntent.ErrorDismissed, TopicFormIntent.ReloadRequested, TopicFormIntent.UploadErrorDismissed,
        TopicFormIntent.DraftWriteFailureDismissed, TopicFormIntent.TogglePreview -> false
        else -> true
    }
