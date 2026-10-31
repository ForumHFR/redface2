package fr.forumhfr.redface2.core.parser.smiley

import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import org.junit.Assert.assertEquals
import org.junit.Test

class SmileyUsageExtractorTest {
    @Test
    fun `every canonical builtin including short codes is counted`() {
        BUILTIN_HFR_SMILEYS.forEach { smiley ->
            assertEquals(mapOf(smiley.token to 1), SmileyUsageExtractor.extract(smiley.token))
        }
    }

    @Test
    fun `exact personal tokens preserve spaces case variants and repeated occurrences`() {
        assertEquals(
            mapOf("[:fictif_a]" to 2, "[:fictif b]" to 1, "[:fictif b:3]" to 1, ":o" to 2, ":jap:" to 1),
            SmileyUsageExtractor.extract("[:fictif_a] :o [:fictif b] [:fictif b:3] [:fictif_a] :o :jap:"),
        )
        assertEquals(
            mapOf("[:Fictif_A]" to 1, "[:fictif_a]" to 1),
            SmileyUsageExtractor.extract("[:Fictif_A][:fictif_a]"),
        )
    }

    @Test
    fun `quote quotemsg code cpp fixed and images exclude nested content`() {
        listOf("quote", "quotemsg=1,2,3", "code", "cpp", "fixed", "img").forEach { opening ->
            val name = opening.substringBefore('=')
            val bbcode = ":o [$opening]:D [quote][:fictif_a][/quote] [code]:jap:[/code][/$name] :)"
            assertEquals(mapOf(":o" to 1, ":)" to 1), SmileyUsageExtractor.extract(bbcode))
        }
    }

    @Test
    fun `unclosed or mismatched excluded blocks stay excluded until the end`() {
        listOf(
            "[quote]:o", "[code][:fictif_a]", "[fixed][quote]:o[/quote] :D",
            "[quote][code]:o[/quote] :D[/code] :jap:", "[CPP]:o", "[img]:o",
        ).forEach { assertEquals(emptyMap<String, Int>(), SmileyUsageExtractor.extract(it)) }
    }

    @Test
    fun `url destinations and bare urls are excluded but formatted labels count`() {
        assertEquals(
            mapOf(":o" to 1, "[:fictif b:3]" to 1),
            SmileyUsageExtractor.extract(
                "[url=https://example.invalid/:o/[:fictif_a]][b]:o[/b][/url] " +
                    "[url]https://example.invalid/:D/[:fictif_a][/url] " +
                    "https://example.invalid/:jap:/[:fictif_a] www.example.invalid/:o " +
                    "ftp://example.invalid/:D [:fictif b:3]",
            ),
        )
    }

    @Test
    fun `formatted bare url followed by a quote does not count the quoted smiley`() {
        assertEquals(
            emptyMap<String, Int>(),
            SmileyUsageExtractor.extract("[b]https://example.invalid[/b][quote] :D[/quote]"),
        )
    }

    @Test
    fun `closing formatting after a bare url preserves the following smiley`() {
        assertEquals(
            mapOf(":o" to 1),
            SmileyUsageExtractor.extract("[b]https://example.invalid[/b]:o"),
        )
    }

    @Test
    fun `recognized opening and closing formatting tags delimit bare urls`() {
        listOf("b", "I", "u", "strike", "spoiler", "email", "#A1B2C3", "list", "*").forEach { tag ->
            assertEquals(
                tag,
                mapOf(":o" to 1),
                SmileyUsageExtractor.extract("https://example.invalid[$tag]:o[/$tag]"),
            )
            assertEquals(
                tag,
                mapOf(":o" to 1),
                SmileyUsageExtractor.extract("[$tag]www.example.invalid[/$tag]:o"),
            )
        }
    }

    @Test
    fun `unknown url query and fragment brackets never split out smiley candidates`() {
        val urls = listOf(
            "https://example.invalid/?q[x]=:D",
            "https://example.invalid/?q[x]=:o",
            "https://example.invalid/?q[x]=:o&other[y]=:D",
            "https://example.invalid/#fragment[x]=:D",
            "https://example.invalid/#fragment[x]=:o",
            "https://example.invalid/?q[x]=:D#fragment[y]=:o",
            "https://example.invalid/?q[outer[inner]]=:D#fragment[x]=:o",
            "https://example.invalid/?q[url_key]=:D#fragment[url_key]=:o",
            "https://example.invalid/?q[/unknown_code]=:D#fragment[/unknown_img]=:o",
            "www.example.invalid/?q[x]=:D#fragment[y]=:o",
            "prefix.www.example.invalid/?q[x]=:D#fragment[y]=:o",
            "custom+scheme://example.invalid/?q[x]=:o#fragment[y]=:D",
        )
        urls.forEach { url ->
            assertEquals(url, mapOf(":jap:" to 1), SmileyUsageExtractor.extract("$url :jap:"))
            assertEquals(url, mapOf(":jap:" to 1), SmileyUsageExtractor.extract("[url]$url[/url] :jap:"))
            assertEquals(
                url,
                mapOf(":o" to 1, ":D" to 1, ":jap:" to 1),
                SmileyUsageExtractor.extract("[url=$url][b]:o[/b] :D[/url] :jap:"),
            )
        }
        assertEquals(
            mapOf(":jap:" to 1),
            SmileyUsageExtractor.extract(
                "https://example.invalid/?q[/unknown_url]=:D#fragment[/unknown_quote]=:o :jap:",
            ),
        )
    }

    @Test
    fun `recognized tags end bare urls even after a query or fragment`() {
        listOf("?q[x]=:D", "#fragment[x]=:D").forEach { suffix ->
            assertEquals(
                mapOf(":o" to 1),
                SmileyUsageExtractor.extract("[b]https://example.invalid/$suffix[/b]:o"),
            )
            assertEquals(
                mapOf(":jap:" to 1),
                SmileyUsageExtractor.extract("https://example.invalid/$suffix[quote] :D[/quote] :jap:"),
            )
        }
    }

    @Test(timeout = 10_000)
    fun `long dotted text without a scheme does not rescan suffixes`() {
        assertEquals(mapOf(":o" to 1), SmileyUsageExtractor.extract("a.".repeat(100_000) + " :o"))
    }

    @Test(timeout = 10_000)
    fun `long unmatched bracket runs do not rescan or copy suffixes`() {
        assertEquals(mapOf(":o" to 1), SmileyUsageExtractor.extract("[".repeat(100_000) + "] :o"))
    }

    @Test(timeout = 10_000)
    fun `long nested invalid brackets and personal tokens remain linear`() {
        listOf("[a", "[:").forEach { prefix ->
            val input = prefix.repeat(100_000) + "]".repeat(100_000) + " :o"
            assertEquals(mapOf(":o" to 1), SmileyUsageExtractor.extract(input))
        }
    }

    @Test
    fun `nested labeled links cannot end an enclosing destination exclusion`() {
        assertEquals(
            mapOf(":jap:" to 1),
            SmileyUsageExtractor.extract(
                "[url]https://example.invalid/[url=target]:o[/url] :D [:fictif_a][/url] :jap:",
            ),
        )
        assertEquals(
            emptyMap<String, Int>(),
            SmileyUsageExtractor.extract("[url]destination [url=target]:o[/url] :D [:fictif_a]"),
        )
    }

    @Test
    fun `builtin boundaries reject words paths times and url fragments`() {
        assertEquals(
            mapOf(":o" to 2, ":D" to 1, ":)" to 1),
            SmileyUsageExtractor.extract(
                "mot:o :orange 10:o0 10:30:45 mot:jap: /:o http://orange.invalid (:o) !:o! :D :)",
            ),
        )
    }

    @Test
    fun `empty plain text and tag parameters do not count`() {
        listOf("", "texte sans smiley", "[b=:o]texte[/b]", "[url=:D]texte[/url]").forEach {
            assertEquals(emptyMap<String, Int>(), SmileyUsageExtractor.extract(it))
        }
    }
}
