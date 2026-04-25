package fr.forumhfr.redface2.feature.topic

data class TopicRequest(
    val cat: Int,
    val post: Int,
    val page: Int,
    val scrollTo: Int?,
)
