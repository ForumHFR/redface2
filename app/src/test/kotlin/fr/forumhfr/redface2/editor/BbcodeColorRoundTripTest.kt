package fr.forumhfr.redface2.editor

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.parser.BbcodeContentParser
import fr.forumhfr.redface2.core.ui.editor.BbcodeAction
import fr.forumhfr.redface2.core.ui.editor.applyBbcodeAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 2B-B end-to-end check : what the colour palette emits via
 * [applyBbcodeAction] is what the local preview parses back into
 * [PostInline.Color]. The HFR colour contract `[#RRGGBB]…[/#RRGGBB]` lives in
 * two modules (`:core:ui` toolbar + `:core:parser` AST) so a JVM round-trip in
 * `:app` (the only module Konsist allows to cross feature/parser in tests)
 * pins the assumption that they stay aligned.
 */
class BbcodeColorRoundTripTest {

    private val parser = BbcodeContentParser()

    @Test
    fun `palette emission parses back into PostInline Color with the same hex`() {
        listOf("#FF0000", "#0000FF", "#008000", "#FF6600", "#808080").forEach { hex ->
            val formatted = applyBbcodeAction(
                action = BbcodeAction.Color(hex),
                text = "swatch",
                selectionStart = 0,
                selectionEnd = "swatch".length,
            )
            val ast = parser.parse(formatted.text)
            val paragraph = ast.blocks.single() as PostBlock.Paragraph
            val color = paragraph.inlines.single() as PostInline.Color
            // Parser normalises to uppercase ; toolbar palette is uppercase already.
            assertEquals(hex.uppercase(), color.colorHex)
            val inner = color.children.single() as PostInline.Text
            assertEquals("swatch", inner.value)
        }
    }
}
