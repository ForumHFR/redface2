package fr.forumhfr.redface2.core.parser.write.poll

import fr.forumhfr.redface2.core.model.write.PollVoteFailureReason
import fr.forumhfr.redface2.core.model.write.PollVoteResult
import java.text.Normalizer
import java.util.Locale
import org.jsoup.Jsoup

/**
 * Classifies HFR's HTTP 200 response to `vote.php`.
 *
 * Live responses put their message in `div.hop`: « Votre vote a bien été pris en compte ! » for
 * acceptance and « Désolé, vous avez déjà voté ! » for a duplicate. Matching is deliberately based
 * on normalised containment rather than fragile whole-string equality. The already-voted marker is
 * checked first. Any response without one of these explicit markers fails closed.
 */
class PollVoteResponseParser {

    fun parse(html: String): PollVoteResult {
        val document = Jsoup.parse(html)
        val message = normalize(document.selectFirst(HOP_SELECTOR)?.text().orEmpty())
        return when {
            message.contains(ALREADY_VOTED_MARKER) -> PollVoteResult.AlreadyVoted
            message.contains(ACCEPTED_MARKER) -> PollVoteResult.Accepted
            else -> PollVoteResult.Failed(PollVoteFailureReason.UnexpectedResponse)
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
        private const val ALREADY_VOTED_MARKER = "vous avez deja vote"
        private const val ACCEPTED_MARKER = "votre vote a bien ete pris en compte"
        private val COMBINING_MARKS = Regex("\\p{M}+")
        private val WHITESPACE = Regex("[\\p{Z}\\s]+")
    }
}
