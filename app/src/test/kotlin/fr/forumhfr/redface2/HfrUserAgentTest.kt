package fr.forumhfr.redface2

import org.junit.Assert.assertEquals
import org.junit.Test

class HfrUserAgentTest {
    @Test
    fun `the user agent includes the exact version flavor and repository`() {
        assertEquals(
            "Redface2/0.72.0-dev+1 (Android; prod; +https://github.com/ForumHFR/redface2)",
            buildHfrUserAgent("0.72.0-dev+1", "prod"),
        )
    }

    @Test
    fun `header delimiters control characters and non ASCII are replaced`() {
        assertEquals(
            "Redface2/0.72.0___ (Android; d_v__; +https://github.com/ForumHFR/redface2)",
            buildHfrUserAgent("0.72.0\r\né", "dév;)"),
        )
        assertEquals(
            "Redface2/unknown (Android; unknown; +https://github.com/ForumHFR/redface2)",
            buildHfrUserAgent("", ""),
        )
    }
}
