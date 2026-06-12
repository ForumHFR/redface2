package fr.forumhfr.redface2.core.ui.post

import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #416 — [collectSmileyUrls] feeds the dead-sprite check, so unlike [collectMeasurableSmileyUrls]
 * (perso-only, measurement pre-seed contract) it must see EVERY smiley URL : HFR serves an unknown
 * `:code:` as a builtin-style sprite that 404s, and that failure is recorded at render time.
 */
class CollectSmileyUrlsTest {

    private val builtin = PostInline.Smiley(
        kind = SmileyKind.Builtin(":zorglub:"),
        imageUrl = "https://forum-images.hardware.fr/icones/smilies/zorglub.gif",
    )
    private val perso = PostInline.Smiley(
        kind = SmileyKind.Perso("rofl"),
        imageUrl = "https://forum-images.hardware.fr/images/perso/rofl.gif",
    )
    private val urlLess = PostInline.Smiley(kind = SmileyKind.Builtin(":)"), imageUrl = null)

    @Test
    fun `collects builtins AND persos, skips url-less smileys`() {
        val urls = collectSmileyUrls(listOf(PostInline.Text("yo "), builtin, perso, urlLess))
        assertEquals(setOf(builtin.imageUrl, perso.imageUrl), urls)
    }

    @Test
    fun `recurses into inline containers like the media walk`() {
        val nested = listOf(
            PostInline.Strong(children = listOf(builtin)),
            PostInline.Link(url = "https://example.org", children = listOf(perso)),
        )
        val urls = collectSmileyUrls(nested)
        assertEquals(setOf(builtin.imageUrl, perso.imageUrl), urls)
    }

    @Test
    fun `measurable collector still skips builtins — contract unchanged`() {
        val measurable = collectMeasurableSmileyUrls(listOf(builtin, perso))
        assertEquals(setOf(perso.imageUrl), measurable)
    }
}
