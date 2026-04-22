package fr.forumhfr.redface2.core.parser.common

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class HfrDateParser(
    private val zoneId: ZoneId = ZoneId.of("Europe/Paris"),
) {
    fun parsePostedAt(toolbarText: String): Instant {
        val normalized = toolbarText
            .replace('\u00A0', ' ')
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

    private companion object {
        val POSTED_AT_REGEX = Regex(
            """Posté le (\d{2})-(\d{2})-(\d{4}) à (\d{2}):(\d{2}):(\d{2})""",
        )
    }
}
