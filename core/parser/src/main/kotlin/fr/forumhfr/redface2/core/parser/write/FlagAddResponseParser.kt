package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.FlagAddResult
import org.jsoup.Jsoup

/**
 * Parses the response HFR returns after a GET to `/user/addflag.php` (place a favourite
 * on a precise topic position, #986).
 *
 * HFR answers HTTP 200 whether the mutation succeeded or not, so the only signal is a
 * literal French sentence in the body : a success page carries « Favori positionné »,
 * while refused / unexpected pages do not.
 *
 * We let Jsoup normalise the document and match on the body text — `body().text()`
 * collapses the markup so deploy-to-deploy styling drift on the `.hop` wrapper does not
 * affect the match. Same approach as [FlagDeleteResponseParser].
 */
class FlagAddResponseParser {

    fun parse(html: String): FlagAddResult {
        val body = Jsoup.parse(html).body().text()
        return if (body.contains(SUCCESS_MARKER, ignoreCase = true)) {
            FlagAddResult.Success
        } else {
            FlagAddResult.Failure
        }
    }

    private companion object {
        // Literal HFR confirmation, lower-cased for case-insensitive match.
        private const val SUCCESS_MARKER: String = "favori positionné"
    }
}
