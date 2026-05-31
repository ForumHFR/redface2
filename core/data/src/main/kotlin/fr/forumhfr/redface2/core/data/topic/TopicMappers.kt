package fr.forumhfr.redface2.core.data.topic

import android.util.Log
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity
import fr.forumhfr.redface2.core.model.Poll
import fr.forumhfr.redface2.core.model.PollOption
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.Topic
import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal object TopicMappers {
    private const val LOG_TAG = "TopicMappers"

    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toEntities(
        topic: Topic,
        fetchedAt: Instant,
        authMode: FetchMode,
    ): Pair<TopicEntity, List<PostEntity>> {
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
            authMode = authMode,
            subcat = topic.subcat,
            canReply = topic.canReply,
        )
        val postEntities = topic.posts.map { post ->
            post.toEntity(topic.cat, topic.post, fetchedAt, authMode)
        }
        return topicEntity to postEntities
    }

    fun toDomain(topic: TopicEntity, posts: List<PostEntity>): Topic {
        val byNumreponse = posts.associateBy { it.numreponse }
        val orderedPosts = topic.numreponses.mapNotNull { byNumreponse[it]?.toDomain() }
        return Topic(
            cat = topic.cat,
            post = topic.post,
            subcat = topic.subcat,
            page = topic.page,
            title = topic.title,
            totalPages = topic.totalPages,
            isFirstPostOwner = topic.isFirstPostOwner,
            posts = orderedPosts,
            poll = topic.pollJson?.let(::decodePollOrNull),
            canReply = topic.canReply,
        )
    }

    /**
     * Best-effort decode for the cached poll JSON. The shape is frozen by [PollDto]
     * `@SerialName` annotations + defaults — but if a future change ever renames a
     * field without a Room migration, this swallow keeps the topic openable (poll
     * shows as missing) instead of crashing the entire screen at cache read.
     * The next authenticated re-fetch overwrites the row with a fresh, well-formed
     * payload.
     */
    private fun decodePollOrNull(pollJson: String): Poll? = runCatching {
        json.decodeFromString(PollDto.serializer(), pollJson).toDomain()
    }.getOrElse { error ->
        Log.w(LOG_TAG, "Failed to decode cached pollJson — degrading to no-poll", error)
        null
    }

    private fun Post.toEntity(
        cat: Int,
        postId: Int,
        fetchedAt: Instant,
        authMode: FetchMode,
    ): PostEntity = PostEntity(
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
        authMode = authMode,
        quoteRef = quoteRef,
        profileId = profileId,
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
        quoteRef = quoteRef,
        profileId = profileId,
    )

    /**
     * On-disk shape of [Poll]. Persisted as JSON in `topic_pages.pollJson` ; every
     * row written since the column existed holds JSON in this exact shape.
     *
     * Defaults are wired up so a missing field on a legacy row degrades gracefully
     * (empty question / no options / no votes) instead of throwing at decode. The
     * next authenticated fetch will overwrite the row with a complete payload.
     * `@SerialName` annotations freeze the JSON keys against any code-side rename ;
     * cf. `PostContent` for the full rationale (same on-disk-contract issue).
     */
    @Serializable
    private data class PollDto(
        @SerialName("question") val question: String = "",
        @SerialName("options") val options: List<PollOptionDto> = emptyList(),
        @SerialName("multipleChoice") val multipleChoice: Boolean = false,
        @SerialName("totalVotes") val totalVotes: Int = 0,
        @SerialName("hasVoted") val hasVoted: Boolean = false,
    )

    @Serializable
    private data class PollOptionDto(
        @SerialName("text") val text: String = "",
        @SerialName("votes") val votes: Int = 0,
        @SerialName("percentage") val percentage: Float = 0f,
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
}
