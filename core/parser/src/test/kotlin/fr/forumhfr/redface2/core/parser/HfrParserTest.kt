package fr.forumhfr.redface2.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfrParserTest {
    private val parser = HfrParser()

    @Test
    fun `parseCitingPosts delegates the real quote-only page to the shared post parser`() {
        val posts = parser.parseCitingPosts(fixture("quote_only_citing_posts.html"))

        // The fixture contains 45 DISTINCT rows for 46 citation occurrences. This assertion pins
        // only the captured endpoint shape; it must never be compared with the target's badge.
        assertEquals(45, posts.size)
        assertTrue(posts.all { post -> post.numreponse > 0 })
        assertTrue(posts.all { post -> post.author.isNotBlank() })
        assertTrue(posts.all { post -> post.content.blocks.isNotEmpty() })
        assertTrue(posts.map { post -> post.date }.distinct().size > 1)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/$name"),
    ) { "Fixture not found: $name" }.readText()
}
