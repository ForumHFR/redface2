package fr.forumhfr.redface2.core.parser

import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostContentParserEmptyLinesTest {
    @Test
    fun `intra-paragraph br between M0 and M1 stays a direct line break`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M0", "M1", expectedBlankLines = 0)
    }

    @Test
    fun `one source empty line renders one web empty line`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M1", "M2", expectedBlankLines = 1)
    }

    @Test
    fun `two source empty lines render two web empty lines`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M2", "M3", expectedBlankLines = 2)
    }

    @Test
    fun `three source empty lines render two web empty lines`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M3", "M4", expectedBlankLines = 2)
    }

    @Test
    fun `four source empty lines render three web empty lines`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M4", "M5", expectedBlankLines = 3)
    }

    @Test
    fun `five source empty lines render three web empty lines`() {
        assertBlankLinesBetween(FIXTURE_RUNS_5_10, "M7", "M8", expectedBlankLines = 3)
    }

    @Test
    fun `six source empty lines render four web empty lines`() {
        assertBlankLinesBetween(FIXTURE_RUNS_1_6, "M5", "M6", expectedBlankLines = 4)
    }

    @Test
    fun `ten source empty lines render six web empty lines without a cap`() {
        assertBlankLinesBetween(FIXTURE_RUNS_5_10, "M8", "M9", expectedBlankLines = 6)
    }

    private fun assertBlankLinesBetween(
        fixtureName: String,
        fromMarker: String,
        toMarker: String,
        expectedBlankLines: Int,
    ) {
        val inlines = parseFixtureParagraph(fixtureName)
        val fromIndex = inlines.indexOfFirst { it == PostInline.Text(fromMarker) }
        val toIndex = inlines.indexOfFirst { it == PostInline.Text(toMarker) }
        assertTrue(
            "missing or reversed markers $fromMarker/$toMarker in $inlines",
            fromIndex >= 0 && toIndex > fromIndex,
        )

        val lineBreaks = inlines.subList(fromIndex + 1, toIndex).count { it is PostInline.LineBreak }
        assertEquals(
            "$fixtureName $fromMarker->$toMarker: Text needs one boundary break plus " +
                "$expectedBlankLines empty lines",
            expectedBlankLines + 1,
            lineBreaks,
        )
    }

    private fun parseFixtureParagraph(fixtureName: String): List<PostInline> {
        val html = requireNotNull(javaClass.getResource("/fixtures/$fixtureName")) {
            "Fixture not found: $fixtureName"
        }.readText()
        val document = Jsoup.parseBodyFragment("<div id=\"para532\">$html</div>")
        val contentElement = requireNotNull(document.selectFirst("div#para532"))
        val paragraphs = PostContentParser()
            .parse(contentElement)
            .ast.blocks
            .filterIsInstance<PostBlock.Paragraph>()
        assertEquals("all marker paragraphs must fold into one text block", 1, paragraphs.size)
        return paragraphs.single().inlines
    }

    private companion object {
        const val FIXTURE_RUNS_1_6 = "fixture-532-runs-1-6.html"
        const val FIXTURE_RUNS_5_10 = "fixture-532-runs-5-10.html"
    }
}
