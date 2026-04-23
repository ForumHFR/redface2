package fr.forumhfr.redface2.core.domain.fixtures

object FixedTopicFixtures {
    const val cat: Int = 13
    const val post: Int = 84540

    val availablePages: List<Int> = listOf(1, 2, 146)

    fun isFixedTopic(
        cat: Int,
        post: Int,
    ): Boolean = cat == FixedTopicFixtures.cat && post == FixedTopicFixtures.post
}
