package fr.forumhfr.redface2.core.data.topic

import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal object TopicMappers {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toEntities(topic: Topic, fetchedAt: Instant): Pair<TopicEntity, List<PostEntity>> {
        val topicEntity = TopicEntity(
            cat = topic.cat,
            post = topic.post,
            page = topic.page,
            title = topic.title,
            totalPages = topic.totalPages,
            isFirstPostOwner = topic.isFirstPostOwner,
            pollJson = topic.poll?.let { json.encodeToString(PollDto.serializer(), it.toDto()) },
            numreponses = topic.posts.map { it.numreponse },
            fetchedAt = fetchedAt,
        )
        val postEntities = topic.posts.map { post -> post.toEntity(topic.cat, topic.post, fetchedAt) }
        return topicEntity to postEntities
    }

    fun toDomain(topic: TopicEntity, posts: List<PostEntity>): Topic {
        val byNumreponse = posts.associateBy { it.numreponse }
        val orderedPosts = topic.numreponses.mapNotNull { byNumreponse[it]?.toDomain() }
        return Topic(
            cat = topic.cat,
            post = topic.post,
            page = topic.page,
            title = topic.title,
            totalPages = topic.totalPages,
            isFirstPostOwner = topic.isFirstPostOwner,
            posts = orderedPosts,
            poll = topic.pollJson?.let { json.decodeFromString(PollDto.serializer(), it).toDomain() },
        )
    }

    private fun Post.toEntity(cat: Int, postId: Int, fetchedAt: Instant): PostEntity = PostEntity(
        cat = cat,
        post = postId,
        numreponse = numreponse,
        author = author,
        date = date,
        content = content,
        avatarUrl = avatarUrl,
        isEditable = isEditable,
        isOwnPost = isOwnPost,
        quotedAuthors = quotedAuthors,
        postIndex = postIndex,
        fetchedAt = fetchedAt,
    )

    private fun PostEntity.toDomain(): Post = Post(
        numreponse = numreponse,
        author = author,
        date = date,
        content = content,
        avatarUrl = avatarUrl,
        isEditable = isEditable,
        isOwnPost = isOwnPost,
        quotedAuthors = quotedAuthors,
        postIndex = postIndex,
    )

    @Serializable
    private data class PollDto(
        val question: String,
        val options: List<PollOptionDto>,
        val multipleChoice: Boolean,
        val totalVotes: Int,
        val hasVoted: Boolean,
    )

    @Serializable
    private data class PollOptionDto(
        val text: String,
        val votes: Int,
        val percentage: Float,
    )

    private fun Poll.toDto(): PollDto = PollDto(
        question = question,
        options = options.map { PollOptionDto(it.text, it.votes, it.percentage) },
        multipleChoice = multipleChoice,
        totalVotes = totalVotes,
        hasVoted = hasVoted,
    )

    private fun PollDto.toDomain(): Poll = Poll(
        question = question,
        options = options.map { PollOption(it.text, it.votes, it.percentage) },
        multipleChoice = multipleChoice,
        totalVotes = totalVotes,
        hasVoted = hasVoted,
    )

    @Suppress("unused")
    private val pollListSerializer = ListSerializer(PollDto.serializer())
}
