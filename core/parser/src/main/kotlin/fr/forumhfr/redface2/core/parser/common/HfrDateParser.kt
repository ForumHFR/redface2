package fr.forumhfr.redface2.core.parser.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class HfrDateParser(
    private val zoneId: ZoneId = ZoneId.of("Europe/Paris"),
) {
    fun parsePostedAt(toolbarText: String): Instant {
        val normalized = toolbarText
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")

        val match = POSTED_AT_REGEX.find(normalized)
            ?: error("Unable to parse HFR post date from: $toolbarText")
        val day = match.groupValues[1]
        val month = match.groupValues[2]
        val year = match.groupValues[3]
        val hour = match.groupValues[4]
        val minute = match.groupValues[5]
        val second = match.groupValues[6]

        return LocalDateTime.of(
            year.toInt(),
            month.toInt(),
            day.toInt(),
            hour.toInt(),
            minute.toInt(),
            second.toInt(),
        ).atZone(zoneId).toInstant()
    }

    /**
     * Parses the short date shown in the private-message inbox listing
     * (`forum1.php?cat=prive`), e.g. `22-07-2015 à 13:19` — same `DD-MM-YYYY à HH:MM`
     * shape as [parsePostedAt] but **without seconds** and without the `Posté le` prefix.
     * The cell also trails the last poster's pseudo (`…13:19<br/><b>Pseudo</b>`); the regex
     * is anchored on the date so the trailing text is ignored. Seconds default to `0`.
     */
    fun parseListDate(text: String): Instant =
        parseListDateOrNull(text) ?: error("Unable to parse HFR list date from: $text")

    /**
     * Tolerant variant of [parseListDate]: returns `null` instead of throwing when the text
     * holds no parseable date, so a listing parser can skip a single malformed row rather
     * than failing the whole page.
     */
    fun parseListDateOrNull(text: String): Instant? {
        val normalized = text
            .replace(' ', ' ')
            .replace(Regex("\\s+"), " ")

        val match = LIST_DATE_REGEX.find(normalized) ?: return null

        return LocalDateTime.of(
            match.groupValues[3].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[1].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            0,
        ).atZone(zoneId).toInstant()
    }

    /**
     * #362 — parses the last-edit timestamp from a post's `div.edited` trailer text:
     * `Message édité par <auteur> le DD-MM-YYYY à HH:MM:SS` (same `à` + seconds shape
     * as [parsePostedAt]). The regex is deliberately **not** anchored at the start of
     * the string: the trailer can be prefixed by the optional « Message cité N fois »
     * citation link (`Message cité 2 fois Message édité par … le …`).
     *
     * Returns `null` when the text holds no edit marker — including the real-fixture
     * case of a `div.edited` that only carries the citation link (post cited but
     * never edited), and the empty string.
     */
    fun parseEditedAtOrNull(text: String): Instant? {
        val normalized = text
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")

        val match = EDITED_AT_REGEX.find(normalized) ?: return null

        return LocalDateTime.of(
            match.groupValues[3].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[1].toInt(),
            match.groupValues[4].toInt(),
            match.groupValues[5].toInt(),
            match.groupValues[6].toInt(),
        ).atZone(zoneId).toInstant()
    }

    private companion object {
        val POSTED_AT_REGEX = Regex(
            """Posté le (\d{2})-(\d{2})-(\d{4}) à (\d{2}):(\d{2}):(\d{2})""",
        )
        val LIST_DATE_REGEX = Regex(
            """(\d{2})-(\d{2})-(\d{4}) à (\d{2}):(\d{2})""",
        )
        val EDITED_AT_REGEX = Regex(
            """Message édité par .+ le (\d{2})-(\d{2})-(\d{4}) à (\d{2}):(\d{2}):(\d{2})""",
        )
    }
}
