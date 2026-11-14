package fr.forumhfr.redface2.core.model.write

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplySubmitResultTest {
    @Test
    fun `success toString exposes presence flags without private URL or coordinates`() {
        val token = "private-csrf-token-1625"
        val subject = "private-subject-1625"
        val pseudo = "private-pseudo-1625"
        val body = "private-body-1625"
        val url = "https://example.test/$subject/sujet_3000003_17.htm" +
            "?hash_check=$token&pseudo=$pseudo&content_form=$body#t1980000302"
        val result = ReplySubmitResult.Success(url, 17, 1980000302, 3000003)

        val rendered = result.toString()

        listOf(url, token, subject, pseudo, body, "17", "1980000302", "3000003").forEach { secret ->
            assertFalse("toString must not expose private URL or coordinates", rendered.contains(secret))
        }
        assertEquals(
            "Success(hasRefreshUrl=true, hasTargetPage=true, hasNumreponse=true, hasTopicId=true)",
            rendered,
        )
    }

    @Test
    fun `success toString handles missing URL and coordinates`() {
        assertEquals(
            "Success(hasRefreshUrl=false, hasTargetPage=false, hasNumreponse=false, hasTopicId=false)",
            ReplySubmitResult.Success(null, null).toString(),
        )
    }
}
