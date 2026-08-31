package fr.forumhfr.redface2.core.parser.write.poll

import fr.forumhfr.redface2.core.model.write.PollCloseResult
import java.text.Normalizer
import java.util.Locale
import org.jsoup.Jsoup

/**
 * Classifies HFR's HTTP 200 response to `close_sondage.php`.
 *
 * The live success response (captured 2026-08-31) puts its message in `div.hop` : « Le sondage a
 * bien été clos ». Matching is deliberately based on normalised containment (case / accents /
 * compacted whitespace folded) rather than fragile whole-string equality, mirroring
 * [PollVoteResponseParser]. HFR's failure shapes (non-owner, already-closed, no poll) were not
 * captured, so anything that is NOT the proven success marker folds to [PollCloseResult.Failure] —
 * the parser never asserts an unproven failure marker.
 */
class PollCloseResponseParser {

    fun parse(html: String): PollCloseResult {
        val document = Jsoup.parse(html)
        val message = normalize(document.selectFirst(HOP_SELECTOR)?.text().orEmpty())
        return if (message.contains(CLOSED_MARKER)) {
            PollCloseResult.Success
        } else {
            PollCloseResult.Failure
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()

    private companion object {
        private const val HOP_SELECTOR = "div.hop"
        private const val CLOSED_MARKER = "le sondage a bien ete clos"
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val WHITESPACE = Regex("[\\p{Z}\\s]+")
    }
}
