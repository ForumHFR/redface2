package fr.forumhfr.redface2.core.parser.search

/**
 * Issue #277 — extracts the topic page from the URL HFR redirects to when probed with
 * `forum2.php?…&page=1&numreponse={N}` (cf. `HfrClient.resolveTopicPageUrl`).
 *
 * Two shapes are recognised, both anchored on the caller's topic id so a URL pointing
 * at ANOTHER topic can never yield a page :
 *
 *  1. **Pretty URL** — `…sujet_{post}_{page}.htm`, relative or absolute, with an
 *     optional `#t{numreponse}` fragment. The path depth varies (the sub-category
 *     segment may or may not be present : `/hfr/gsmgpspda/android/…` vs
 *     `/hfr/gsmgpspda/…`), so the match keys on the `sujet_…` segment only — never
 *     on the number of path segments. Live example (2026-06-10, anonymous) :
 *     `/hfr/gsmgpspda/redface-dev-sujet_35421_3.htm#t2786758` → page 3.
 *  2. **Query URL fallback** — a `forum2.php` URL whose query carries BOTH
 *     `post={post}` and `page={page}` (should HFR ever answer with the legacy form
 *     instead of the pretty path).
 *
 * Anything else returns `null` — the caller falls back to the page parsed from the
 * search href (which HFR always serialises as `1`, hence this resolver).
 */
object TopicPageUrlParser {

    /**
     * Returns the page encoded in [url] for the topic [post], or `null` when [url]
     * does not designate a page of that topic.
     */
    fun parseTopicPageFromUrl(url: String, post: Int): Int? =
        parsePrettyUrl(url, post) ?: parseForum2QueryUrl(url, post)

    private fun parsePrettyUrl(url: String, post: Int): Int? =
        SUJET_SEGMENT_REGEX.findAll(url)
            .firstOrNull { it.groupValues[1].toIntOrNull() == post }
            ?.groupValues?.get(2)?.toIntOrNull()

    private fun parseForum2QueryUrl(url: String, post: Int): Int? {
        // The post anchor applies to the fallback too — without it a forum2.php URL of
        // a DIFFERENT topic would happily contribute its page.
        val isSameTopic = url.contains("forum2.php") &&
            QUERY_POST_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() == post
        return if (isSameTopic) QUERY_PAGE_REGEX.find(url)?.groupValues?.get(1)?.toIntOrNull() else null
    }

    /**
     * `sujet_{topicId}_{page}.htm` — same lookbehind trick as
     * `ReplySubmitResponseParser.SUJET_SEGMENT_REGEX` : a listing URL like
     * `liste_sujet_1_2.htm` has `_` right before `sujet`, which the lookbehind
     * rejects, while a real thread segment is preceded by `/` or `-`. The trailing
     * `.htm` pins the segment to the end of the path (fragment may follow).
     */
    private val SUJET_SEGMENT_REGEX: Regex =
        Regex("""(?<![a-z_])sujet_(\d+)_(\d+)\.htm""", RegexOption.IGNORE_CASE)

    private val QUERY_POST_REGEX: Regex = Regex("""[?&]post=(\d+)""")
    private val QUERY_PAGE_REGEX: Regex = Regex("""[?&]page=(\d+)""")
}
