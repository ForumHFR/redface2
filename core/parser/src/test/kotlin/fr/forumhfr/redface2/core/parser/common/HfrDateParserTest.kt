package fr.forumhfr.redface2.core.parser.common

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #362 — contract of [HfrDateParser.parseEditedAtOrNull] against the real `div.edited`
 * text shapes observed in the committed fixtures (`topic_page_single.html`,
 * `topic_khakha_page_2.html`):
 *
 * - `Message édité par <auteur> le DD-MM-YYYY à HH:MM:SS` (plain edit trailer);
 * - the same with `&nbsp;` (U+00A0) around the `à`, which is how HFR actually ships it;
 * - prefixed by the optional « Message cité N fois » citation link text;
 * - the citation link ALONE (post cited but never edited) → null;
 * - empty text → null.
 */
class HfrDateParserTest {
    private val parser = HfrDateParser()

    @Test
    fun `parseEditedAtOrNull parses a plain edit trailer`() {
        val editedAt = parser.parseEditedAtOrNull(
            "Message édité par jubjub le 14-03-2016 à 12:09:00",
        )

        // 2016-03-14 is CET (UTC+1) in Europe/Paris.
        assertEquals(Instant.parse("2016-03-14T11:09:00Z"), editedAt)
    }

    @Test
    fun `parseEditedAtOrNull normalizes the non-breaking spaces HFR ships around the a`() {
        // Raw fixture shape: `…le 14-03-2016&nbsp;à&nbsp;12:09:00` — Jsoup's text()
        // surfaces the entities as U+00A0, which the parser must normalize to spaces.
        val editedAt = parser.parseEditedAtOrNull(
            "Message édité par Mitch2Pain le 16-03-2016\u00A0à\u00A012:35:19",
        )

        assertEquals(Instant.parse("2016-03-16T11:35:19Z"), editedAt)
    }

    @Test
    fun `parseEditedAtOrNull finds the edit trailer behind a citation link prefix`() {
        // The « Message cité N fois » link is part of the same div.edited, so the
        // edit marker is NOT at the start of the text — the regex must not be anchored.
        val editedAt = parser.parseEditedAtOrNull(
            "Message cité 2 fois Message édité par fafarex le 16-03-2016\u00A0à\u00A015:43:43",
        )

        assertEquals(Instant.parse("2016-03-16T14:43:43Z"), editedAt)
    }

    @Test
    fun `parseEditedAtOrNull returns null for a citation-only trailer`() {
        // Real case (topic_khakha_page_2.html, n°16628222): div.edited carries only the
        // citation link because the post is cited but was never edited.
        assertNull(parser.parseEditedAtOrNull("Message cité 1 fois"))
    }

    @Test
    fun `parseEditedAtOrNull returns null for empty text`() {
        assertNull(parser.parseEditedAtOrNull(""))
    }
}
