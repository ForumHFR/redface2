package fr.forumhfr.redface2.feature.editor

import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [TopicFormState.canSubmit] for the create-topic (New) flow against the
 * #213 « cat without sub-category » contract :
 *
 *  - a cat WITH sub-categories (`hasSubcategorySelect = true`) still requires a
 *    `selectedSubcat > 0` before submit is allowed ;
 *  - a cat WITHOUT sub-category (`hasSubcategorySelect = false`, e.g. cat IA)
 *    is postable with `subcat = 0`, so submit is allowed even though
 *    `selectedSubcat` is null.
 *
 * Also pins the #459 upload guard for BOTH modes (#953 F5) : an in-flight image
 * upload blocks submit, mirroring [PostEditorState.canSubmit].
 */
class TopicFormStateTest {

    private fun newState(
        hasSubcategorySelect: Boolean,
        selectedSubcat: Int?,
    ): TopicFormState =
        TopicFormState(
            mode = TopicFormMode.New,
            cat = 32,
            subcat = null,
            topicId = null,
            page = null,
            numreponse = null,
            subject = TextFieldValue("Titre"),
            draft = TextFieldValue("Corps"),
            hasSubcategorySelect = hasSubcategorySelect,
            selectedSubcat = selectedSubcat,
        )

    private fun editFirstPostState(isUploading: Boolean = false): TopicFormState =
        TopicFormState(
            mode = TopicFormMode.EditFirstPost,
            cat = 23,
            subcat = 401,
            topicId = 35395,
            page = 1,
            numreponse = 1,
            subject = TextFieldValue("Titre"),
            draft = TextFieldValue("Corps"),
            selectedSubcat = 401,
            isUploading = isUploading,
        )

    @Test
    fun `New with a cat WITHOUT sub-category allows submit even with a null selectedSubcat`() {
        assertTrue(newState(hasSubcategorySelect = false, selectedSubcat = null).canSubmit)
    }

    @Test
    fun `New with a cat WITH sub-categories blocks submit while selectedSubcat is null`() {
        assertFalse(newState(hasSubcategorySelect = true, selectedSubcat = null).canSubmit)
    }

    @Test
    fun `New with a cat WITH sub-categories allows submit once a positive subcat is picked`() {
        assertTrue(newState(hasSubcategorySelect = true, selectedSubcat = 550).canSubmit)
    }

    @Test
    fun `New blocks submit when the subject is blank even on a cat without sub-category`() {
        val state = newState(hasSubcategorySelect = false, selectedSubcat = null)
            .copy(subject = TextFieldValue("   "))
        assertFalse(state.canSubmit)
    }

    @Test
    fun `New blocks submit when the draft is blank even on a cat without sub-category`() {
        val state = newState(hasSubcategorySelect = false, selectedSubcat = null)
            .copy(draft = TextFieldValue(""))
        assertFalse(state.canSubmit)
    }

    @Test
    fun `New blocks submit while an image upload is in flight (#953 F5)`() {
        val state = newState(hasSubcategorySelect = false, selectedSubcat = null)
            .copy(isUploading = true)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `EditFirstPost blocks submit while an image upload is in flight (#953 F5)`() {
        assertTrue("sanity: the base EditFirstPost state must be submittable", editFirstPostState().canSubmit)
        assertFalse(editFirstPostState(isUploading = true).canSubmit)
    }
}
