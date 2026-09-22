package fr.forumhfr.redface2.core.data.di

import fr.forumhfr.redface2.core.data.smiley.PersonalSmileyPreviewResolver
import fr.forumhfr.redface2.core.data.smiley.PersonalSmileyRegistry
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.parser.HfrParser
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformBindingsModuleTest {

    @Test
    fun `provided preview parser resolves a picker token only after BBCode parsing`() {
        val registry = PersonalSmileyRegistry()
        registry.register(
            registry.capture(),
            "jap_yvele",
            "https://example.com/jap_yvele.gif",
        )
        val previewParser = PlatformBindingsModule.provideBbcodePreviewParser(
            parser = HfrParser(),
            personalSmileyPreviewResolver = PersonalSmileyPreviewResolver(registry),
        )

        val content = previewParser.parsePreview("avant [:jap_yvele] après")

        val paragraph = content.blocks.single() as PostBlock.Paragraph
        val smiley = paragraph.inlines.filterIsInstance<PostInline.Smiley>().single()
        assertEquals("https://example.com/jap_yvele.gif", smiley.imageUrl)
    }
}
