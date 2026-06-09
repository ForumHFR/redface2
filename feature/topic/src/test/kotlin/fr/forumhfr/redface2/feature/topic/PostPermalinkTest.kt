package fr.forumhfr.redface2.feature.topic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #362 — pins the permalink contract: the documented topic-page read URL
 * (`protocol-hfr.md`) + the `#t{numreponse}` post anchor.
 */
class PostPermalinkTest {
    @Test
    fun `builds the canonical forum2 URL with the post anchor`() {
        assertEquals(
            "https://forum.hardware.fr/forum2.php?config=hfr.inc" +
                "&cat=13&post=84540&page=2#t16628102",
            buildPostPermalink(cat = 13, post = 84540, page = 2, numreponse = 16628102),
        )
    }

    @Test
    fun `keeps page 1 explicit so the anchor always resolves on the served page`() {
        assertEquals(
            "https://forum.hardware.fr/forum2.php?config=hfr.inc" +
                "&cat=23&post=21748&page=1#t520051",
            buildPostPermalink(cat = 23, post = 21748, page = 1, numreponse = 520051),
        )
    }
}
