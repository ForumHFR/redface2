package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.FlagDeleteResult
import org.jsoup.Jsoup

/**
 * Parses the response HFR returns after a GET to `/user/delflag.php` (remove a single
 * drapeau, #99).
 *
 * HFR answers HTTP 200 whether the deletion succeeded or not, so the only signal is a
 * literal French sentence in the body : a success page carries « Drapeau effacé avec
 * succès » (wrapped in a `<div class="hop">`), while an already-removed / refused
 * drapeau lands on a listing page that does **not** contain it (e.g. « Aucun favori
 * n'est repertorié »).
 *
 * We let Jsoup normalise the document and match on the body text — `body().text()`
 * collapses the markup so deploy-to-deploy styling drift on the `.hop` wrapper does not
 * affect the match. Same approach as [ReplySubmitResponseParser].
 *
 * Pinned by [FlagDeleteResponseParserTest] against the captured fixtures
 * `core/parser/src/test/resources/fixtures/flag_delete_success.html` and
 * `flag_delete_already_removed.html`.
 */
class FlagDeleteResponseParser {

    fun parse(html: String): FlagDeleteResult {
        val body = Jsoup.parse(html).body().text()
        return if (body.contains(SUCCESS_MARKER, ignoreCase = true)) {
            FlagDeleteResult.Success
        } else {
            FlagDeleteResult.Failure
        }
    }

    private companion object {
        // Literal HFR confirmation, lower-cased for case-insensitive match.
        private const val SUCCESS_MARKER: String = "drapeau effacé avec succès"
    }
}
