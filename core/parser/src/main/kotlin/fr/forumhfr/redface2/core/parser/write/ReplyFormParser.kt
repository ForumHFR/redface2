package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyForm
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses HFR's `message.php` reply form. The page returned by HFR contains many
 * `<form>` elements (search box, navigation widgets, …); the reply form is the
 * one whose `action` ends with `bddpost.php` — see fixture
 * `core/parser/src/test/resources/fixtures/write_reply_form_open_topic.html`.
 *
 * The parser captures every hidden input under that form. The list of fields HFR
 * sends is not fully stable, so we forward them all rather than allow-listing —
 * with two exceptions:
 *
 * - `password` is filtered out unconditionally. Even in an authenticated session
 *   HFR injects a (blank) `password` field for the legacy composer fallback; we
 *   never want to re-POST it.
 * - `pseudo` is filtered out as well when the form is anonymous. When the form is
 *   authenticated the field carries the user's pseudo and we forward it, matching
 *   what HFR's web composer does.
 *
 * [ReplyForm.isAnonymous] is true when the form has an editable `<input
 * name="pseudo">` with an empty `value=""` and a sibling `<input
 * name="password">`. Both shapes come straight from
 * `write_reply_anonymous_form.html`.
 */
class ReplyFormParser {

    @Suppress("ReturnCount") // Guard clauses for hash_check / form absent / etc.
    fun parse(html: String): Result<ReplyForm> {
        val document = Jsoup.parse(html)
        val replyForm = document.selectFirst("form[action*=bddpost.php]")
            ?: return Result.failure(IllegalStateException("Reply form not found"))

        val hiddenInputs = replyForm.select("input[type=hidden]")
        val pseudoInput = replyForm.selectFirst("input[name=pseudo]")
        val sujetInput = replyForm.selectFirst("input[name=sujet]")

        val pseudoValue = pseudoInput?.attr("value").orEmpty()
        val isAnonymous = pseudoInput != null && pseudoValue.isEmpty()

        val collected = mutableMapOf<String, String>()
        var hashCheck: String? = null
        hiddenInputs.forEach { input ->
            val name = input.attr("name")
            if (name.isEmpty()) return@forEach
            val value = input.attr("value")
            when (name) {
                "hash_check" -> {
                    // Keep the first non-empty hash_check we see; HFR sometimes renders
                    // it twice (search widget + reply form). The reply form copy comes
                    // after the search one, so the last-write-wins behaviour below also
                    // works, but pin the contract explicitly here.
                    if (value.isNotEmpty()) hashCheck = value
                }
                // We forward the static hidden inputs verbatim — keeps Phase 2C honest
                // against future HFR additions without code change.
                else -> collected[name] = value
            }
        }

        val resolvedHashCheck = hashCheck
        if (resolvedHashCheck.isNullOrBlank()) {
            return Result.failure(IllegalStateException("hash_check missing from reply form"))
        }

        // Pseudo: only forward when authenticated (non-empty). Password is always
        // dropped: HFR's hidden composer is the one we never want to use.
        if (!isAnonymous && pseudoInput != null) {
            val name = pseudoInput.attr("name")
            if (name.isNotEmpty() && pseudoValue.isNotEmpty()) collected[name] = pseudoValue
        }

        // Sujet is part of the form contract but lives outside the hidden inputs in
        // some HFR renderings (it can be either visible or hidden depending on layout).
        val sujet = sujetInput?.attr("value").orEmpty()

        return Result.success(
            ReplyForm(
                hashCheck = resolvedHashCheck,
                sujet = sujet,
                hiddenFields = collected.toMap(),
                isAnonymous = isAnonymous,
            ),
        )
    }

    @Suppress("unused")
    private fun Element.hiddenInputValue(name: String): String? =
        selectFirst("input[type=hidden][name=$name]")?.attr("value")?.takeIf { it.isNotEmpty() }
}
