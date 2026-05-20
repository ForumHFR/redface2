package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyFailureReason
import fr.forumhfr.redface2.core.model.write.ReplySubmitResult
import org.jsoup.Jsoup

/**
 * Parses the response HFR returns after a POST to `bddpost.php` (reply / quote)
 * or `bdd.php` (edit post — Phase 2D #147).
 *
 * Success and failure are surfaced by a literal French sentence in the body, plus
 * a `<meta http-equiv="Refresh" content="N; url=…">` header on success that
 * carries the URL HFR wants the client to land on. We match on substrings because
 * HFR wraps the message in styled `<div class="hop">` markup that varies between
 * deploys ; the underlying text doesn't.
 *
 * Reply and edit successes use different literal sentences («Votre réponse a été
 * postée avec succès » vs. « Votre message a été édité avec succès ») but
 * everything else — refresh URL shape, error variants — is identical, so a
 * single parser covers both flows.
 *
 * Each match maps to a concrete fixture under
 * `core/parser/src/test/resources/fixtures/write_*` ; pinned by tests in
 * `ReplySubmitResponseParserTest`.
 */
class ReplySubmitResponseParser {

    fun parse(html: String): ReplySubmitResult {
        // Fast path: HFR's "invalid token" path returns a bare text/plain payload
        // (~99 bytes, no HTML envelope). Match first to avoid relying on Jsoup for
        // body-less inputs.
        val trimmedHead = html.trimStart().take(MAX_HEAD_PROBE)
        if (looksLikeInvalidTokenPlainText(trimmedHead)) {
            return ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)
        }

        // Other paths land on a full HFR-styled HTML page — let Jsoup normalise it.
        val document = Jsoup.parse(html)
        val body = document.body().text()

        return when {
            INVALID_TOKEN_PATTERNS.any { body.contains(it, ignoreCase = true) } ->
                ReplySubmitResult.Failure(ReplyFailureReason.InvalidHashCheck)

            body.contains(EMPTY_MESSAGE_MARKER, ignoreCase = true) ->
                ReplySubmitResult.Failure(ReplyFailureReason.EmptyMessage)

            body.contains(ANTI_FLOOD_MARKER, ignoreCase = true) ->
                ReplySubmitResult.Failure(ReplyFailureReason.AntiFlood)

            body.contains(TOPIC_LOCKED_MARKER, ignoreCase = true) ->
                ReplySubmitResult.Failure(ReplyFailureReason.TopicLocked)

            body.contains(SUCCESS_MARKER, ignoreCase = true) -> parseSuccess(html)

            // Phase 2D (#147) : edit-post success uses its own sentence ; the
            // refresh URL shape is identical (`sujet_{topic}_{page}.htm#t{numreponse}`)
            // so `parseSuccess` reuses the same extraction.
            body.contains(EDIT_SUCCESS_MARKER, ignoreCase = true) -> parseSuccess(html)

            else -> ReplySubmitResult.Failure(ReplyFailureReason.Unknown)
        }
    }

    private fun parseSuccess(html: String): ReplySubmitResult.Success {
        // Use the raw HTML rather than Jsoup so we keep the literal `1; url=…`
        // structure intact ; Jsoup normalises the attribute order in older releases.
        val refresh = META_REFRESH_REGEX.find(html)?.groupValues?.getOrNull(1)?.trim()
        val page = refresh?.let { url -> PAGE_NUMBER_REGEX.find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        return ReplySubmitResult.Success(refreshUrl = refresh, targetPage = page)
    }

    private fun looksLikeInvalidTokenPlainText(head: String): Boolean {
        if (head.isEmpty()) return false
        val lower = head.lowercase()
        // Plain-text payload : no opening tag, just the literal HFR sentence.
        return !lower.startsWith("<") &&
            INVALID_TOKEN_PATTERNS.any { lower.contains(it, ignoreCase = true) }
    }

    private companion object {
        private const val MAX_HEAD_PROBE = 256

        // Substrings of the literal HFR messages. Lower-cased for case-insensitive match.
        private val INVALID_TOKEN_PATTERNS: List<String> = listOf(
            "une erreur est survenue lors de l'envoi des données",
        )
        private const val EMPTY_MESSAGE_MARKER: String =
            "remplir tous les champs avant de poster"
        private const val ANTI_FLOOD_MARKER: String =
            "consécutives dans un intervalle de 10 minutes"
        private const val TOPIC_LOCKED_MARKER: String = "sujet a été fermé"
        private const val SUCCESS_MARKER: String = "votre réponse a été postée avec succès"

        // Phase 2D (#147) — captured 2026-05-17 on the test post 2784595 ;
        // see `write_edit_success_response.html`.
        private const val EDIT_SUCCESS_MARKER: String = "votre message a été édité avec succès"

        private val META_REFRESH_REGEX: Regex =
            Regex("""<meta[^>]*http-equiv="Refresh"[^>]*content="\d+\s*;\s*url=([^"]+)""", RegexOption.IGNORE_CASE)

        // HFR's success refresh URLs look like `…/sujet_35395_20.htm#bas` — the second
        // integer between underscores is the page.
        private val PAGE_NUMBER_REGEX: Regex = Regex("""sujet_\d+_(\d+)""", RegexOption.IGNORE_CASE)
    }
}
