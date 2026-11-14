package fr.forumhfr.redface2.feature.editor

import fr.forumhfr.redface2.core.ui.post.PostMediaDiskCachePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** ADR-018 § 9: the editor target alone decides whether preview media may reach Coil's disk cache. */
class PostEditorMediaPolicyTest {
    @Test
    fun `private message edit keeps preview media out of the disk cache`() {
        val state = PostEditorState(PostEditorMode.Edit, null, null, 101, 3, null,
            target = PostEditorTarget.PrivateMessage(42))
        assertEquals(PostMediaDiskCachePolicy.DISABLED, PostEditorChromeState(state).mediaDiskCachePolicy)
    }

    @Test
    fun `public reply and public edit keep the disk cache`() {
        val reply = PostEditorState(PostEditorMode.Reply, 23, 42, null, 1, 0)
        assertEquals(PostMediaDiskCachePolicy.ENABLED, PostEditorChromeState(reply).mediaDiskCachePolicy)
        val edit = reply.copy(mode = PostEditorMode.Edit, numreponse = 101)
        assertEquals(PostMediaDiskCachePolicy.ENABLED, PostEditorChromeState(edit).mediaDiskCachePolicy)
    }
}
