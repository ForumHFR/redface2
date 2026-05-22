package fr.forumhfr.redface2.core.parser.smiley

/**
 * Phase 2F-B (#11 partial) — extracts the HFR `user_id` the editor needs to call the wiki
 * smiley search endpoint.
 *
 * HFR's web composer embeds the logged-in user's id in the JS bootstrap call
 * `find_smilies_timer('hfr.inc', <user_id>)` (cf. `/compressed/message.js`). The endpoint
 * accepts any id including `0` for anonymous probes, but pages a logged-in user's
 * favourites first when a real id is passed — so we plumb the form value when it's there.
 *
 * Returns `null` when the marker is absent (anonymous form or HFR changed the JS). Returns
 * the parsed `Int` otherwise, **including `0`** when the form embeds `find_smilies_timer(…, 0)`.
 * The `0` case is rare (HFR uses it on the anonymous composer) but distinct from « marker
 * absent » : the caller can still tell « we saw the marker, value was 0 » vs « marker absent »
 * — useful for future diagnostics that want to distinguish the two paths. In practice both
 * collapse to `user_id=0` at the wire (the repository uses `userId ?: 0`).
 *
 * Pure JVM, regex-based : `Jsoup.parse` would scrub the inline JS (it's inside `<script>`
 * tags) and a full JS tokenizer is overkill for one number.
 */
internal object SmileyUserIdExtractor {

    private val PATTERN = Regex("""find_smilies_timer\(\s*'[^']*'\s*,\s*(\d+)\s*\)""")

    fun extract(html: String): Int? {
        // Accept any non-negative int including `0` : the wiki endpoint takes `0` as the
        // anonymous probe id, so dropping it as if it were a parse failure would mask a
        // legitimate value the repository can forward as-is. Only the `null` returned by
        // `find()` (marker absent) and `toIntOrNull()` (corrupt number) signal a true
        // « unknown » to the caller.
        val raw = PATTERN.find(html)?.groupValues?.getOrNull(1)
        return raw?.toIntOrNull()?.takeIf { it >= 0 }
    }
}
