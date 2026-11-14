package fr.forumhfr.redface2.core.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the exact grammar of every draft key (#405). The key is the row identity of a cached
 * draft: any change to the format silently orphans every previously saved draft, so these strings
 * are a contract, not an implementation detail. The reply key must NOT vary with the quoted post.
 */
class EditorDraftKeyTest {

    @Test
    fun `report key is distinct from the private message composer`() {
        assertEquals("report", EditorDraftKey.report())
        assertNotEquals(EditorDraftKey.mpCompose(), EditorDraftKey.report())
    }

    @Test
    fun `offered drafts are kept for replies and new compositions, never for edits - ED121`() {
        val replies = listOf(EditorDraftKey.reply(29, 123456), EditorDraftKey.mpReply(42))
        val compositions = listOf(EditorDraftKey.newTopic(29), EditorDraftKey.mpCompose())
        val others = listOf(
            EditorDraftKey.editPost(29, 7), EditorDraftKey.editFirstPost(29, 7), EditorDraftKey.report(),
        )
        assertEquals(replies, (replies + compositions + others).filter(EditorDraftKey::isReply))
        assertEquals(compositions, (replies + compositions + others).filter(EditorDraftKey::composesHeaders))
        assertEquals(
            replies + compositions, (replies + compositions + others).filter(EditorDraftKey::keepsOfferedDrafts),
        )
    }

    @Test
    fun `reply key carries cat and topic id`() {
        assertEquals("reply:29:123456", EditorDraftKey.reply(cat = 29, topicId = 123456))
    }

    @Test
    fun `new topic key carries cat only`() {
        assertEquals("newtopic:29", EditorDraftKey.newTopic(cat = 29))
    }

    @Test
    fun `edit post key carries cat and numreponse`() {
        // numreponse is unique per category, so the key carries cat to stay globally unique.
        assertEquals("edit:23:35395", EditorDraftKey.editPost(cat = 23, numreponse = 35395))
    }

    @Test
    fun `edit first post key is distinct from edit post key`() {
        assertEquals("editfp:23:35395", EditorDraftKey.editFirstPost(cat = 23, numreponse = 35395))
    }

    @Test
    fun `mp reply key carries thread id only`() {
        assertEquals("mpreply:42", EditorDraftKey.mpReply(threadId = 42))
    }

    @Test
    fun `private edit key is separate from replies and public edits`() {
        val edit = EditorDraftKey.mpEdit(42)
        assertEquals("mpedit:42", edit)
        assertNotEquals(EditorDraftKey.mpReply(42), edit)
        assertNotEquals(EditorDraftKey.editPost(23, 42), edit)
        assertEquals(true, EditorDraftKey.isPrivate(edit))
        assertEquals(false, EditorDraftKey.isReply(edit))
        assertEquals(false, EditorDraftKey.keepsOfferedDrafts(edit))
    }

    @Test
    fun `mp compose key is a fixed singleton`() {
        assertEquals("mpnew", EditorDraftKey.mpCompose())
    }
}
