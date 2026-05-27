package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import fr.forumhfr.redface2.core.database.entities.FetchMode
import fr.forumhfr.redface2.core.database.entities.PostEntity
import fr.forumhfr.redface2.core.database.entities.TopicEntity

@Dao
interface TopicDao {
    @Query("SELECT * FROM topic_pages WHERE cat = :cat AND post = :post AND page = :page LIMIT 1")
    suspend fun getTopicPage(cat: Int, post: Int, page: Int): TopicEntity?

    @Query("SELECT * FROM posts WHERE cat = :cat AND numreponse IN (:numreponses)")
    suspend fun getPostsByNumreponse(cat: Int, numreponses: List<Int>): List<PostEntity>

    @Upsert
    suspend fun upsertTopicPage(topic: TopicEntity)

    @Upsert
    suspend fun upsertPosts(posts: List<PostEntity>)

    @Transaction
    suspend fun upsertTopicPageWithPosts(topic: TopicEntity, posts: List<PostEntity>) {
        upsertTopicPage(topic)
        upsertPosts(posts)
    }

    /**
     * Atomically writes a topic page + posts unless an [FetchMode.AUTHENTICATED] row
     * already lives at the same `(cat, post, page)` triple. Used by the anonymous
     * prefetch path to avoid downgrading per-user signals (`isOwnPost`, `isEditable`)
     * carried by an existing auth row.
     *
     * Returns `true` when the write actually happened, `false` when an authenticated
     * row was preserved. The whole read-check-write runs inside a single Room
     * transaction so a concurrent authenticated fetch cannot slip between the read
     * and the write.
     */
    @Transaction
    suspend fun upsertTopicPageWithPostsUnlessAuthenticated(
        topic: TopicEntity,
        posts: List<PostEntity>,
    ): Boolean {
        val existing = getTopicPage(topic.cat, topic.post, topic.page)
        if (existing != null && existing.authMode == FetchMode.AUTHENTICATED) {
            return false
        }
        upsertTopicPage(topic)
        upsertPosts(posts)
        return true
    }

    // Topic cache maintenance — wipes only the topic page + post tables so the next read
    // through `observeTopicPage` goes through `fetchAndPersist` and re-parses HFR's HTML
    // with the latest parser code. Used by the alpha "Vider le cache des topics" action
    // when a parser/AST evolution makes existing cached `PostBlock`s look stale.
    //
    // Deliberately narrow: does NOT touch flag tables (handled by `FlagRepository`), auth
    // cookies (DataStore + cookie jar), or user preferences (proxy / DataStore). The
    // ordering posts → topic_pages deletes child-like rows before their page metadata,
    // keeping the operation safe if a future schema adds foreign keys.
    @Query("DELETE FROM posts")
    suspend fun deleteAllPosts()

    @Query("DELETE FROM topic_pages")
    suspend fun deleteAllTopicPages()

    @Transaction
    suspend fun clearTopicCache() {
        deleteAllPosts()
        deleteAllTopicPages()
    }
}
