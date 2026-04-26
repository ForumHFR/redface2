package fr.forumhfr.redface2.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
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
}
