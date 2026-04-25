package fr.forumhfr.redface2.core.model

import java.time.Instant

data class Post(
    val numreponse: Int,
    val author: String,
    val date: Instant,
    val content: String,
    val contentAst: PostContent,
    val avatarUrl: String?,
    val isEditable: Boolean,
    val isOwnPost: Boolean,
    val quotedAuthors: List<String>,
    val postIndex: Int?,
)
