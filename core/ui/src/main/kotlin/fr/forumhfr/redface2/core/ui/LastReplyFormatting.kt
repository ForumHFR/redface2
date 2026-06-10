package fr.forumhfr.redface2.core.ui

/**
 * REST listing timestamp shape, as printed by HFR's REST API (`last_post_date`):
 * `YYYY-MM-DD HH:mm` — no seconds, no timezone. Proven by the live fixtures
 * (`rest_cat23_participated.json`, `rest_topics_cat23_subcat550_p1.json`) and the
 * mapper tests (`RestForumMappersTest`, `RestFlagMappersTest`).
 */
private val REST_TIMESTAMP = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}:\d{2})""")

/**
 * Formats a raw "last reply" timestamp for list rows so the app matches what the HFR
 * website prints in its listings: the absolute compact form `DD-MM-YYYY à HH:mm`
 * (year always shown, even for the current year — exactly like the web).
 *
 * Observed source formats (#325):
 * - **REST mappers** (`TopicSummary.lastReplyAt`, `Flag.lastReplyAt`, both fed from the
 *   REST `last_post_date` field): `YYYY-MM-DD HH:mm`, e.g. `2026-05-01 17:07`. This is
 *   the shape this function rewrites, to `01-05-2026 à 17:07`.
 * - **Search HTML parser** (`SearchTopicResult.lastReplyAt` via
 *   `SearchResultParser.parseLastReply`): already the web form `DD-MM-YYYY à HH:mm`
 *   (NBSP normalised to plain spaces upstream), e.g. `24-09-2025 à 06:48`. It does not
 *   match the REST shape and therefore passes through unchanged — which is the desired
 *   display.
 *
 * Strict fallback rule: any input that does not match the REST shape **in its
 * entirety** is returned as-is. This function never throws and never turns a non-empty
 * input into an empty one; it is a mechanical reorder, not a date parser (it does not
 * validate that day/month values are calendar-plausible).
 */
fun formatLastReplyTimestamp(raw: String): String {
    val match = REST_TIMESTAMP.matchEntire(raw) ?: return raw
    val year = match.groupValues[1]
    val month = match.groupValues[2]
    val day = match.groupValues[3]
    val time = match.groupValues[4]
    return "$day-$month-$year à $time"
}
