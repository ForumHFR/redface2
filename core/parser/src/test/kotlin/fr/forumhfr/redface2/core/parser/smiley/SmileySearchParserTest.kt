package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.EditorSmileySource
import fr.forumhfr.redface2.core.model.PostBlock
import fr.forumhfr.redface2.core.model.PostInline
import fr.forumhfr.redface2.core.model.SmileyKind
import fr.forumhfr.redface2.core.parser.TopicPageParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2F-B (#11 partial) — pins the wiki-search parser against the real HFR fragment
 * `smiley_search_jap.html` captured 2026-05-22 (cf. `.source.txt` for capture details).
 *
 * The capture is rich enough to exercise every token shape the parser must preserve : simple
 * `[:axellay]`, dashed `[:55-]`, underscored `[:menkahoure_4]`, whitespaced `[:haha jap]`,
 * `:N`-variant `[:eneytihi:5]`, plus the actual duplicate rows HFR emits on some perso
 * variants (`[:grozibouille:1]`, `[:pradar:3]`, `[:redneck wannabe:1]`).
 */
class SmileySearchParserTest {

    private val parser = SmileySearchParser()

    @Test
    fun `parses the real jap fixture into deduplicated WIKI entries`() {
        val results = parser.parse(readFixture("smiley_search_jap.html"))

        assertFalse("real fixture should yield results", results.isEmpty())
        assertTrue(
            "all entries come from the wiki search endpoint",
            results.all { it.source == EditorSmileySource.WIKI },
        )
        // Every token in the fixture starts with `[:` and ends with `]` — invariant for perso
        // smileys. Any drift here would mean the parser pulled a stray <img> (e.g. an icon).
        assertTrue(
            "tokens must keep the perso `[:…]` shape",
            results.all { it.token.startsWith("[:") && it.token.endsWith("]") },
        )
        // Each entry must point to a real perso image URL.
        assertTrue(
            "image URLs must be absolute HFR perso paths",
            results.all {
                it.imageUrl.startsWith("https://forum-images.hardware.fr/images/perso/")
            },
        )
    }

    @Test
    fun `preserves whitespaces inside perso names`() {
        // `[:haha jap]`, `[:swedish chef]`, `[:cerveau jap]`, `[:double clic]` all carry an
        // inline space that the BBCode token must keep — a naive `trim()` or whitespace strip
        // would break the round-trip.
        val results = parser.parse(readFixture("smiley_search_jap.html"))
        listOf("[:haha jap]", "[:swedish chef]", "[:cerveau jap]", "[:double clic]").forEach { token ->
            assertNotNull(
                "expected $token to survive verbatim — got tokens=${results.map { it.token }}",
                results.firstOrNull { it.token == token },
            )
        }
    }

    @Test
    fun `preserves underscored and dashed perso names`() {
        val results = parser.parse(readFixture("smiley_search_jap.html"))
        listOf("[:menkahoure_4]", "[:dd_005]", "[:55-]", "[:2244]").forEach { token ->
            assertNotNull(
                "expected $token to survive verbatim",
                results.firstOrNull { it.token == token },
            )
        }
    }

    @Test
    fun `preserves perso variants with colon and numeric suffix`() {
        // `[:name:N]` is the HFR « variant » syntax — the second integer must not be split off
        // by the parser, even though it looks like a `:code:` builtin separator.
        val results = parser.parse(readFixture("smiley_search_jap.html"))
        listOf("[:aioka:4]", "[:eneytihi:5]", "[:cliowilliams:8]").forEach { token ->
            assertNotNull(
                "expected variant token $token to survive verbatim",
                results.firstOrNull { it.token == token },
            )
        }
    }

    @Test
    fun `deduplicates entries by token plus url`() {
        // The live fixture contains some perso variants twice in a row (e.g.
        // `[:grozibouille:1]`). The parser must keep each unique pair exactly once so the
        // picker UI does not render duplicate cells.
        val results = parser.parse(readFixture("smiley_search_jap.html"))
        val tokens = results.map { it.token to it.imageUrl }
        assertEquals(
            "all (token, url) pairs must be unique",
            tokens.size,
            tokens.toSet().size,
        )
    }

    @Test
    fun `drops entries missing src or alt`() {
        val fragment = """
            <img src="https://forum-images.hardware.fr/images/perso/ok.gif" alt="[:ok]" />
            <img src="" alt="[:noimg]" />
            <img src="https://forum-images.hardware.fr/images/perso/notoken.gif" alt="" />
            <img alt="[:nourl]" />
        """.trimIndent()

        val results = parser.parse(fragment)
        assertEquals(listOf("[:ok]"), results.map { it.token })
    }

    @Test
    fun `falls back to title when alt is empty`() {
        // Defensive : `alt` is the canonical attribute but a legacy variant could ship only
        // `title`. The HFR live fixture always sends both, so this case lives only in this
        // synthetic minimal HTML.
        val fragment = """
            <img src="https://forum-images.hardware.fr/images/perso/x.gif" title="[:fromtitle]" />
        """.trimIndent()
        val results = parser.parse(fragment)
        assertEquals(listOf("[:fromtitle]"), results.map { it.token })
    }

    @Test
    fun `wiki and rendered-post fixtures produce the same naked registry key`() {
        // #873 — both are real HFR captures. A parser-priority or trimming drift between these
        // paths would make a populated registry miss the exact token rendered by the preview.
        val wikiKey = parser.parse(readFixture("smiley_search_jap.html"))
            .first { it.token == "[:jap_yvele]" }
            .let { PersonalSmileyNameExtractor.extract(it.token) }
        val postKey = TopicPageParser().parse(readFixture("topic_khakha_page_146.html"))
            .posts
            .flatMap { it.content.blocks }
            .filterIsInstance<PostBlock.Paragraph>()
            .flatMap { it.inlines }
            .filterIsInstance<PostInline.Smiley>()
            .mapNotNull { it.kind as? SmileyKind.Perso }
            .first { it.name == "jap_yvele" }
            .name

        assertEquals("jap_yvele", wikiKey)
        assertEquals(wikiKey, postKey)
    }

    @Test
    fun `empty fragment yields empty list`() {
        assertEquals(emptyList<Any>(), parser.parse(""))
    }

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "Fixture not found: $name"
        }.bufferedReader().use { it.readText() }
}
