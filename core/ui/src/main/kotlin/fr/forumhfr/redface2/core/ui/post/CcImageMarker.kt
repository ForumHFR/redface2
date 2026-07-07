package fr.forumhfr.redface2.core.ui.post

import java.net.URLDecoder

/**
 * #256 — detection of the community "cc-image" marker, a query parameter (`hfr-cc-image=true`)
 * that userscript-era HFR extensions append to emoji image URLs (e.g.
 * `…/emojis-micro/<codepoint>.png?hfr-cc-image=true&raw=true`) to declare "this `[img]` is a
 * one-line emoji glyph, size it like text".
 *
 * The check is **render-time only** and applies to the ORIGINAL URL carried by the AST (the
 * `:core:parser` `sanitizeImageHref` preserves the query string verbatim, and any redirect target —
 * e.g. `raw.githubusercontent.com` — is a Coil concern that never reaches this code). It never
 * alters the AST, the link semantics, the MediaCounter symmetry or the block-promotion path: its
 * only consumers are the inline SIZING fast-path in `imageDisplayBox` and the measurement-probe
 * exclusion in `collectMeasurableImageUrl` (both in PostRenderer).
 *
 * Matching contract (pure JVM, no `android.net.Uri` — `:core:ui` unit tests run on the JVM):
 *  - only the **query** component is inspected: the substring between the first `?` and the first
 *    following `#` (a marker in the fragment or in the path never matches);
 *  - the query is split into `&`-separated pairs; each pair's name and value are percent-decoded
 *    (application/x-www-form-urlencoded, so `+` decodes to a space) and compared **exactly and
 *    case-sensitively** to `hfr-cc-image` / `true` — no naive `contains`, no case folding;
 *  - a pair whose name cannot be decoded (malformed percent-encoding) cannot be the marker and is
 *    ignored; a URL with no query, or any unparseable shape, simply doesn't match.
 *
 * DUPLICATE RULE (decided before implementation, most conservative option): the fast-path applies
 * only when the marker is UNAMBIGUOUS — at least one `hfr-cc-image` pair is present AND **every**
 * occurrence decodes exactly to `true`. Any occurrence carrying anything else (`false`, an empty or
 * missing value, an undecodable value) disqualifies the whole URL, i.e. "false wins" over "first
 * wins". Rationale: a non-match merely falls back to the normal measured-sizing path (worst case one
 * async probe, correct rendering), while a wrong match would pin a real photo to a 16sp square — so
 * ambiguity must resolve to NOT fast-pathing.
 */
@Suppress("ReturnCount") // No-query guards + trailing verdict.
internal fun isCcImageUrl(url: String): Boolean {
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return false
    val fragmentStart = url.indexOf('#')
    // A `?` after the first `#` belongs to the fragment: the URL has no query component at all
    // (`e.png#frag?hfr-cc-image=true` must NOT match — Codex gate catch).
    if (fragmentStart in 0..<queryStart) return false
    val rawQuery = url.substring(queryStart + 1, if (fragmentStart >= 0) fragmentStart else url.length)
    if (rawQuery.isEmpty()) return false
    val occurrences = rawQuery.split('&').mapNotNull(::ccMarkerOccurrenceOrNull)
    // Duplicate rule: at least one marker occurrence, and every one of them exactly "true".
    return occurrences.isNotEmpty() && occurrences.all { it.value == CC_IMAGE_MARKER_VALUE }
}

/**
 * Parses one raw `&`-separated query pair; non-null only for decodable `hfr-cc-image` pairs.
 * Distinguishing "not a marker pair" (null) from "a marker pair with a missing/undecodable value"
 * ([CcMarkerOccurrence] with a null [CcMarkerOccurrence.value], which disqualifies) is the whole
 * point of the wrapper.
 */
@Suppress("ReturnCount") // Ignore-this-pair guards, cheaper than nesting three levels of if/else.
private fun ccMarkerOccurrenceOrNull(pair: String): CcMarkerOccurrence? {
    if (pair.isEmpty()) return null
    val separator = pair.indexOf('=')
    val rawName = if (separator >= 0) pair.substring(0, separator) else pair
    // An undecodable name cannot be the marker — the pair is ignored, not disqualifying.
    val name = decodeQueryComponent(rawName) ?: return null
    if (name != CC_IMAGE_MARKER_NAME) return null
    // A missing or undecodable value stays null and disqualifies through the "all true" rule.
    val value = if (separator >= 0) decodeQueryComponent(pair.substring(separator + 1)) else null
    return CcMarkerOccurrence(value)
}

/** One `hfr-cc-image` occurrence; [value] is null when the value was missing or undecodable. */
private data class CcMarkerOccurrence(val value: String?)

/**
 * Percent-decodes one query name/value, or null when malformed (e.g. a truncated `%z` escape).
 * Uses the charset-NAME overload: `URLDecoder.decode(String, Charset)` needs API 33 (minSdk is 29).
 */
private fun decodeQueryComponent(component: String): String? =
    runCatching { URLDecoder.decode(component, "UTF-8") }.getOrNull()

private const val CC_IMAGE_MARKER_NAME = "hfr-cc-image"
private const val CC_IMAGE_MARKER_VALUE = "true"
