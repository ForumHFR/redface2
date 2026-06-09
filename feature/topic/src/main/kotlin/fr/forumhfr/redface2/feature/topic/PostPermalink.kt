package fr.forumhfr.redface2.feature.topic

/**
 * #362 — canonical HFR permalink to a single post, copied to the clipboard from the
 * per-post menu ([PostMenuSheet]).
 *
 * Shape: the topic-page read URL documented in `docs/specs/protocol-hfr.md`
 * (`/forum2.php?config=hfr.inc&cat={cat}&post={post}&page={page}`) plus the per-post
 * anchor `#t{numreponse}` HFR renders on every post (`<a name="t{numreponse}">`), the
 * same fragment shape the app itself consumes in deep links (cf.
 * `docs/specs/navigation.md` § lien vers un post spécifique). The host is hardcoded:
 * the permalink targets the real forum for sharing, never a test override base URL.
 *
 * Pure top-level function (same spirit as [citationCountsByNumreponse]) so the URL
 * contract is unit-testable without Compose.
 */
internal fun buildPostPermalink(cat: Int, post: Int, page: Int, numreponse: Int): String =
    "https://forum.hardware.fr/forum2.php?config=hfr.inc" +
        "&cat=$cat&post=$post&page=$page#t$numreponse"
