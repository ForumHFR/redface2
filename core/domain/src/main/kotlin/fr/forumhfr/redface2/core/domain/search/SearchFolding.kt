package fr.forumhfr.redface2.core.domain.search

import java.text.Normalizer

/**
 * Case- and accent-insensitive folding shared by the app's client-side searches (#739).
 *
 * HFR titles, MP subjects and settings labels are French: a user who types `cafe` expects « café »
 * to match — and the other way round. A plain `contains(query, ignoreCase = true)` does not give
 * that (the Drapeaux search bug of #739). Folding BOTH sides through this function does:
 *
 * 1. Unicode NFD decomposition — every precomposed letter becomes base letter + combining marks
 *    (`é` → `e` + U+0301), whichever normalisation form the input arrived in;
 * 2. removal of every combining mark (Unicode general category `M`, not just the single
 *    « Combining Diacritical Marks » block), so `é`, `ê`, `ë`, `ç`, `ñ`… lose their diacritics;
 * 3. locale-agnostic lowercasing;
 * 4. the two French ligatures NFD does NOT decompose are spelled out: `œ` → `oe`, `æ` → `ae`
 *    (`Œ`/`Æ` included, they are lowercased first), so `coeur` matches « cœur ».
 *
 * Pure JVM (`java.text.Normalizer`), no Android dependency. Known trade-off, accepted for a French
 * forum: NFD also decomposes a few non-Latin letters (e.g. Cyrillic `й` → `и`, kana with dakuten),
 * which makes those rare searches slightly more permissive too. Whitespace is left untouched —
 * callers trim the query themselves when they want to.
 *
 * Used by the Drapeaux / DT search (`filterFlagsByQuery`, `filterDtItemsByQuery`), the Forum
 * listing search (`matchesTopicQuery`) and the Settings search (`filterSettingsSections`).
 */
fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase()
        .replace("œ", "oe")
        .replace("æ", "ae")

/**
 * True when [query], folded with [foldForSearch], is a substring of this string folded the same way
 * (#739). Case- and accent-insensitive on both sides: `"Café".containsFolded("cafe")` and
 * `"cafe".containsFolded("Café")` are both true. An empty [query] always matches (empty substring),
 * so callers that want « blank query = no filter » must short-circuit before calling this.
 */
fun String.containsFolded(query: String): Boolean = foldForSearch().contains(query.foldForSearch())

private val COMBINING_MARKS = Regex("\\p{M}+")
