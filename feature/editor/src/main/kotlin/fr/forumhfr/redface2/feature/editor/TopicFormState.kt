package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import fr.forumhfr.redface2.core.domain.editor.BbcodeValidation
import fr.forumhfr.redface2.core.domain.editor.validateBbcodeDraft
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction

/**
 * MVI state of the topic-level form. Phase 2D #148 only covers
 * [TopicFormMode.EditFirstPost] ; [TopicFormMode.New] still surfaces a
 * placeholder until #149 lands.
 *
 * Reuses [SubmitError] from `PostEditorState.kt` for the error envelope —
 * the failure variants HFR can return are identical for post-level and
 * topic-level edits (same `bdd.php` endpoint, same classifier).
 */
data class TopicFormState(
    val mode: TopicFormMode,
    val cat: Int?,
    val subcat: Int?,
    val topicId: Int?,
    val page: Int?,
    val numreponse: Int?,
    val subject: TextFieldValue = TextFieldValue(),
    val draft: TextFieldValue = TextFieldValue(),
    val preview: PostContent = PostContent(blocks = emptyList()),
    val isPreviewVisible: Boolean = false,
    val validation: BbcodeValidation = BbcodeValidation.Idle,
    val selectedSubcat: Int? = null,
    val subcategoryChoices: List<TopicFormSubcategoryChoice> = emptyList(),
    val pollPresent: Boolean = false,
    val pollEditable: Boolean = false,
    val signatureEnabled: Boolean = false,
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
    val isLoadingForm: Boolean = false,
    val isSubmitting: Boolean = false,
    val submitError: SubmitError? = null,
    /**
     * True once we hydrated subject + draft from the parsed form ; prevents
     * `InvalidHashCheck` refetch from clobbering edits.
     */
    val formHydratedFromServer: Boolean = false,
    /** Mirror of [PostEditorState.optionsHydratedFromForm] for the same anti-clobber reason. */
    val optionsHydratedFromForm: Boolean = false,
) {
    /**
     * Submit is allowed when : we know the full routing context (cat / subcat /
     * topicId / page / numreponse, for FP edit), the user has typed a non-blank
     * subject AND content, the form was successfully loaded, and we are not
     * already submitting.
     */
    val canSubmit: Boolean
        get() = mode == TopicFormMode.EditFirstPost &&
            cat != null &&
            (subcat != null && subcat > 0) &&
            topicId != null &&
            numreponse != null &&
            page != null &&
            (selectedSubcat != null && selectedSubcat > 0) &&
            subject.text.isNotBlank() &&
            draft.text.isNotBlank() &&
            !isLoadingForm &&
            !isSubmitting
}

/**
 * Intents emitted by [TopicFormScreen]. Mirrors `PostEditorIntent` but for the
 * topic-level surface — `SubjectChanged` and `SubcatSelected` are the only
 * intents the post-level editor does not need.
 */
sealed interface TopicFormIntent {
    data class SubjectChanged(val value: TextFieldValue) : TopicFormIntent
    data class ContentChanged(val value: TextFieldValue) : TopicFormIntent
    data class ToolbarActionClicked(val action: BbcodeAction) : TopicFormIntent
    data object TogglePreview : TopicFormIntent
    data object SubmitClicked : TopicFormIntent
    data object ErrorDismissed : TopicFormIntent
    data class SubcatSelected(val id: Int) : TopicFormIntent
    data class ToggleSignature(val enabled: Boolean) : TopicFormIntent
    data class ToggleSmileyDisabled(val disabled: Boolean) : TopicFormIntent
    data class ToggleEmailNotification(val enabled: Boolean) : TopicFormIntent
}

/**
 * One-shot effects from [TopicFormViewModel]. Same shape as
 * `PostEditorEffect.SubmitSucceeded(targetPage, scrollTo)` so the navigation
 * host can reuse the existing topic-refresh logic.
 */
sealed interface TopicFormEffect {
    data class SubmitSucceeded(
        val targetPage: Int?,
        val scrollTo: Int? = null,
    ) : TopicFormEffect
}

internal fun TopicFormState.withDraft(updated: TextFieldValue): TopicFormState =
    copy(
        draft = updated,
        validation = validateBbcodeDraft(updated.text),
        submitError = if (updated.text != draft.text) null else submitError,
    )
