package fr.forumhfr.redface2.core.domain.blacklist

import java.text.Normalizer
import java.util.Locale

/**
 * Canonical match key for an HFR pseudo, used by the blacklist to decide whether a post author is
 * hidden. A plain `lowercase()` is not enough (Codex review on #509): HFR pseudos can carry
 * non-breaking / doubled whitespace, mixed Unicode normalisation forms, and stray invisible format
 * characters, all of which must collapse to the same key so "Foo", "foo" and "foo " match.
 *
 * Steps, in order:
 * 1. drop Unicode format characters (general category Cf: zero-width space/joiners, BOM, word
 *    joiner, bidi controls…) that never carry meaning in a pseudo;
 * 2. collapse every run of whitespace (incl. non-breaking spaces — [Char.isWhitespace] covers them)
 *    to a single ASCII space, and trim the ends;
 * 3. normalise to Unicode NFC — **without** stripping accents: HFR shows accented pseudos verbatim,
 *    so "Crème" and "Creme" are deliberately different users;
 * 4. lowercase with [Locale.ROOT] for a stable, locale-independent key.
 *
 * Returns an empty string for blank input (the repository treats that as "nothing to block").
 */
fun canonicalizePseudo(raw: String): String {
    val collapsed = buildString {
        var pendingSpace = false
        var started = false
        for (ch in raw) {
            when {
                Character.getType(ch.code) == Character.FORMAT.toInt() -> Unit
                ch.isWhitespace() -> if (started) pendingSpace = true
                else -> {
                    if (pendingSpace) append(' ')
                    append(ch)
                    pendingSpace = false
                    started = true
                }
            }
        }
    }
    return Normalizer.normalize(collapsed, Normalizer.Form.NFC).lowercase(Locale.ROOT)
}
