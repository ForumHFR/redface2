package fr.forumhfr.redface2.core.parser.smiley

/**
 * #873 — extracts the process-wide lookup key from HFR's `[:name]` perso-smiley token.
 *
 * HFR's picker inserts `this.alt`, so `alt` wins when both attributes carry a perso token.
 * Whitespace outside the token is ignored to preserve the historical post-parser tolerance, while
 * the name between `[:` and `]` is returned exactly: no case folding, Unicode normalisation or
 * internal whitespace removal. Consequently, `name` and `name:N` remain distinct keys.
 */
object PersonalSmileyNameExtractor {

    fun extract(token: String): String? {
        val trimmedToken = token.trim()
        return trimmedToken
            .takeIf(PERSONAL_SMILEY_TOKEN::matches)
            ?.substring(2, trimmedToken.length - 1)
    }

    fun extract(alt: String, title: String): String? =
        extract(alt) ?: extract(title)

    private val PERSONAL_SMILEY_TOKEN = Regex("""^\[:[^]]+]$""")
}
