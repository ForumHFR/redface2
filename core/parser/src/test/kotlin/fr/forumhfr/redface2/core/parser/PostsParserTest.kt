package fr.forumhfr.redface2.core.parser

import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostsParserTest {
    private val parser = PostsParser()

    @Test
    fun `messageModo author cell marks the real reduced moderation fixture`() {
        val post = parser.parsePosts(
            Jsoup.parse(fixture("moderation/moderation_post_75210915.html")),
        ).single()

        assertTrue(post.isModerationPost)
    }

    @Test
    fun `normal real topic fixture keeps every post unmarked`() {
        val posts = parser.parsePosts(Jsoup.parse(fixture("topic_page_single.html")))

        assertTrue("the normal fixture must exercise at least one post", posts.isNotEmpty())
        assertTrue(posts.none { post -> post.isModerationPost })
    }

    @Test
    fun `nested messageModo token and neighbouring moderation post do not contaminate normal post`() {
        val moderationPost = fixture("moderation/moderation_post_75210915.html")
        val normalPostWithNestedToken = moderationPost
            .replace("class=\"messCase1 messageModo \"", "class=\"messCase1\"")
            .replace(
                "<div id=\"para75210915\"><p></p></div>",
                """
                <div id="para75210915">
                  <table class="citation"><tr><td class="messageModo">citation</td></tr></table>
                </div>
                """.trimIndent(),
            )

        val posts = parser.parsePosts(Jsoup.parse(normalPostWithNestedToken + moderationPost))

        assertFalse(posts[0].isModerationPost)
        assertTrue(posts[1].isModerationPost)
    }

    private fun fixture(name: String): String = requireNotNull(
        javaClass.getResource("/fixtures/$name"),
    ) { "Fixture not found: $name" }.readText()
}
