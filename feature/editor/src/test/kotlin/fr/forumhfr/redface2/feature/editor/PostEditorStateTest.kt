package fr.forumhfr.redface2.feature.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins `PostEditorState.canSubmit` against the #213 subcat contract : `subcat = 0`
 * (a category without sub-category, e.g. cat IA) is postable, the SUBCAT_UNKNOWN
 * sentinel (-1) and the `null` unknown stay blocking.
 */
class PostEditorStateTest {

    @Test
    fun `edit cannot submit even nonblank text until initial body is known`() {
        val unknown = replyState(subcat = 0).copy(mode = PostEditorMode.Edit, numreponse = 101)
        assertTrue(unknown.isAwaitingInitialBody)
        assertFalse(unknown.canSubmit)
        val known = unknown.copy(draftHydratedFromForm = true)
        assertFalse(known.isAwaitingInitialBody)
        assertTrue(known.canSubmit)
        assertFalse(replyState(subcat = 0).isAwaitingInitialBody)
    }

    private fun replyState(subcat: Int?, isUploading: Boolean = false): PostEditorState =
        PostEditorState(
            mode = PostEditorMode.Reply,
            cat = 32,
            topicId = 12345,
            numreponse = null,
            page = 1,
            subcat = subcat,
            bodyText = "Hello!",
            isUploading = isUploading,
        )

    @Test
    fun `canSubmit is true with subcat zero (cat without sub-category)`() {
        assertTrue(replyState(subcat = 0).canSubmit)
    }

    @Test
    fun `canSubmit is true with a positive subcat`() {
        assertTrue(replyState(subcat = 550).canSubmit)
    }

    @Test
    fun `canSubmit is false with the SUBCAT_UNKNOWN sentinel`() {
        assertFalse(replyState(subcat = -1).canSubmit)
    }

    @Test
    fun `canSubmit is false when subcat is null`() {
        assertFalse(replyState(subcat = null).canSubmit)
    }

    @Test
    fun `canSubmit is false while an image upload is in flight (#459)`() {
        assertFalse(replyState(subcat = 0, isUploading = true).canSubmit)
    }
}
