package fr.forumhfr.redface2.core.parser

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `message tones are parsed from linked post number icons only`() {
        val posts = parser.parsePosts(Jsoup.parse(fixture("topic_dev_p75_msgicon.html")))

        assertEquals(28, posts.size)
        assertEquals(20, posts.single { it.numreponse == 2800266 }.msgIcon)
        assertEquals(20, posts.single { it.numreponse == 2800292 }.msgIcon)
        assertEquals(10, posts.single { it.numreponse == 2800342 }.msgIcon)
        assertEquals(6, posts.single { it.numreponse == 2800343 }.msgIcon)
        assertNull(posts.single { it.numreponse == 2800250 }.msgIcon)
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
