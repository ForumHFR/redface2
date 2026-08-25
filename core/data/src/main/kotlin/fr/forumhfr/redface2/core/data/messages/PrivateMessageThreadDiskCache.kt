package fr.forumhfr.redface2.core.data.messages

import fr.forumhfr.redface2.core.database.dao.PrivateMessageContentDao
import fr.forumhfr.redface2.core.database.dao.StoredPrivateMessageThreadPage
import fr.forumhfr.redface2.core.database.entities.PrivateMessageEntity
import fr.forumhfr.redface2.core.database.entities.PrivateMessageThreadPageEntity
import fr.forumhfr.redface2.core.model.Post
import fr.forumhfr.redface2.core.model.messages.PrivateMessageThread
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Narrow persistence façade; private-message repositories must not reach database primitives. */
interface PrivateMessageThreadDiskCache {
    suspend fun read(userId: String, threadId: Int, page: Int): PrivateMessageThread?

    suspend fun replace(userId: String, thread: PrivateMessageThread, fetchedAt: Instant)

    suspend fun clearForUser(userId: String)

    suspend fun clearAll()
}

/**
 * Maps only the semantic rendering model to Room. Raw responses, request URLs, reply forms and
 * authentication fields never cross this façade. The component is deliberately silent because
 * identifiers and payloads are private (#316).
 */
@Singleton
class RoomPrivateMessageThreadDiskCache @Inject internal constructor(
    private val contentDao: PrivateMessageContentDao,
) : PrivateMessageThreadDiskCache {
    override suspend fun read(userId: String, threadId: Int, page: Int): PrivateMessageThread? =
        contentDao.getPage(canonicalRoomUserId(userId), threadId, page)?.toModel()

    override suspend fun replace(
        userId: String,
        thread: PrivateMessageThread,
        fetchedAt: Instant,
    ) {
        val canonicalUserId = canonicalRoomUserId(userId)
        contentDao.replacePage(
            page = thread.toPageEntity(canonicalUserId, fetchedAt),
            messages = thread.messages.mapIndexed { ordinal, message ->
                message.toEntity(canonicalUserId, thread.threadId, thread.page, ordinal)
            },
            maxPages = MAX_PAGES_PER_ACCOUNT,
        )
    }

    override suspend fun clearForUser(userId: String) {
        contentDao.clearForUser(canonicalRoomUserId(userId))
    }

    override suspend fun clearAll() {
        contentDao.clearAll()
    }

    private fun StoredPrivateMessageThreadPage.toModel(): PrivateMessageThread =
        PrivateMessageThread(
            threadId = page.threadId,
            subject = page.subject,
            correspondent = page.correspondent,
            messages = messages.map { message -> message.toModel() },
            page = page.page,
            totalPages = page.totalPages,
            canReply = page.canReply,
            isMultiRecipient = page.isMultiRecipient,
        )

    private fun PrivateMessageThread.toPageEntity(
        userId: String,
        fetchedAt: Instant,
    ): PrivateMessageThreadPageEntity = PrivateMessageThreadPageEntity(
        userId = userId,
        threadId = threadId,
        page = page,
        subject = subject,
        correspondent = correspondent,
        totalPages = totalPages,
        canReply = canReply,
        isMultiRecipient = isMultiRecipient,
        fetchedAt = fetchedAt,
    )

    private fun Post.toEntity(
        userId: String,
        threadId: Int,
        page: Int,
        ordinal: Int,
    ): PrivateMessageEntity = PrivateMessageEntity(
        userId = userId,
        threadId = threadId,
        page = page,
        numreponse = numreponse,
        ordinal = ordinal,
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
        editedAt = editedAt,
        citedCount = citedCount,
        signature = signature,
    )

    private fun PrivateMessageEntity.toModel(): Post = Post(
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
        editedAt = editedAt,
        citedCount = citedCount,
        signature = signature,
    )

    private companion object {
        const val MAX_PAGES_PER_ACCOUNT = 5
    }
}

/** The existing Room convention: case-fold only, with no whitespace or format-character rewrite. */
internal fun canonicalRoomUserId(pseudo: String): String = pseudo.lowercase()
