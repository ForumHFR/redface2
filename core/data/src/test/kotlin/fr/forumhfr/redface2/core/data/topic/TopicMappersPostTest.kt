package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.PostContent
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicMappersPostTest {
    @Test
    fun `moderation marker survives both topic cache mapper directions`() {
        val topic = Topic(
            cat = 13,
            post = 21512,
            subcat = Topic.SUBCAT_UNKNOWN,
            title = "fixture",
            posts = listOf(moderationPost()),
            page = 1,
            totalPages = 1,
            isFirstPostOwner = false,
            poll = null,
        )

        val (topicEntity, postEntities) = TopicMappers.toEntities(
            topic = topic,
            fetchedAt = Instant.EPOCH,
            authMode = FetchMode.ANONYMOUS,
        )

        assertTrue(postEntities.single().isModerationPost)
        assertTrue(TopicMappers.toDomain(topicEntity, postEntities).posts.single().isModerationPost)
    }

    @Test
    fun `message tone survives both topic cache mapper directions`() {
        val topic = Topic(
            cat = 13,
            post = 21512,
            subcat = Topic.SUBCAT_UNKNOWN,
            title = "fixture",
            posts = listOf(moderationPost().copy(msgIcon = 20)),
            page = 1,
            totalPages = 1,
            isFirstPostOwner = false,
            poll = null,
        )

        val (topicEntity, postEntities) = TopicMappers.toEntities(
            topic = topic,
            fetchedAt = Instant.EPOCH,
            authMode = FetchMode.ANONYMOUS,
        )

        assertEquals(20, postEntities.single().msgIcon)
        assertEquals(20, TopicMappers.toDomain(topicEntity, postEntities).posts.single().msgIcon)
    }

    private fun moderationPost(): Post = Post(
        numreponse = 75210915,
        author = "Modération",
        date = Instant.EPOCH,
        content = PostContent(emptyList()),
        avatarUrl = null,
        isEditable = false,
        isOwnPost = false,
        quotedAuthors = emptyList(),
        postIndex = null,
        isModerationPost = true,
    )
}
