package fr.forumhfr.redface2.feature.topic

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import org.junit.Assert.assertEquals
import org.junit.Test

class TopicHtmlRendererTest {
    @Test
    fun parseTopicBlocks_groupsContiguousInlineNodesIntoSingleParagraph() {
        val blocks = parseTopicBlocks("""<a href="https://forum.hardware.fr/">link</a> ----> <b>next</b>""")

        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as TopicContentBlock.Paragraph
        val rendered = renderInlineTopicHtml(
            html = paragraph.html,
            linkStyles = TextLinkStyles(style = SpanStyle()),
        )

        assertEquals("link ----> next", rendered.text)
    }

    @Test
    fun renderInlineTopicHtml_preservesSeparatorsAroundInlineNodes() {
        val rendered = renderInlineTopicHtml(
            html = """<a href="https://forum.hardware.fr/">link</a> ----> <b>next</b>""",
            linkStyles = TextLinkStyles(style = SpanStyle()),
        )

        assertEquals("link ----> next", rendered.text)
    }
}
