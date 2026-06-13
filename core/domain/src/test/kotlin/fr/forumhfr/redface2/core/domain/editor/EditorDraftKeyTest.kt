package fr.forumhfr.redface2.core.domain.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the exact grammar of every draft key (#405). The key is the row identity of a cached
 * draft: any change to the format silently orphans every previously saved draft, so these strings
 * are a contract, not an implementation detail. The reply key must NOT vary with the quoted post.
 */
class EditorDraftKeyTest {

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
    fun `mp compose key is a fixed singleton`() {
        assertEquals("mpnew", EditorDraftKey.mpCompose())
    }
}
