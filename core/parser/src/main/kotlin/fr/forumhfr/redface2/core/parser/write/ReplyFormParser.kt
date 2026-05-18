package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyForm
import org.jsoup.Jsoup

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

        // Iterate every <input> below the reply form (hidden, text, password, …) so
        // we can apply the explicit allow/deny rules below regardless of the input
        // type HFR uses. Restricting to `input[type=hidden]` would silently drop the
        // user-typed pseudo field (which HFR renders as `type=text` even when the
        // user is authenticated) and would *not* protect against password input
        // either (which has `type=password`, not hidden) — both deny rules are
        // necessary, and both come from a single pass over the full input list.
        val allInputs = replyForm.select("input[name]")
        val pseudoInput = replyForm.selectFirst("input[name=pseudo]")
        val sujetInput = replyForm.selectFirst("input[name=sujet]")

        val pseudoValue = pseudoInput?.attr("value").orEmpty()
        val isAnonymous = pseudoInput != null && pseudoValue.isEmpty()

        val collected = mutableMapOf<String, String>()
        var hashCheck: String? = null
        allInputs.forEach { input ->
            val name = input.attr("name")
            if (name.isEmpty()) return@forEach
            val type = input.attr("type").lowercase()
            // Hard deny: password is never echoed back to HFR (HFR's hidden composer
            // fallback would re-authenticate over an existing cookie — never the path
            // we want). The `password` *name* check is belt-and-braces in case HFR
            // ever ships a non-`type=password` element with that name.
            if (type == "password" || name == "password") return@forEach
            val value = input.attr("value")
            when (name) {
                "hash_check" -> {
                    // HFR sometimes renders hash_check twice (search widget + reply
                    // form). Last non-empty write wins; the reply form copy appears
                    // after the search one in the document, so this picks up the
                    // authoritative value naturally.
                    if (value.isNotEmpty()) hashCheck = value
                }
                "pseudo" -> {
                    // Only forward when authenticated (non-empty value). On the
                    // anonymous composer, HFR re-types the same field as an editable
                    // text input — we still skip it.
                    if (!isAnonymous && pseudoValue.isNotEmpty()) collected[name] = pseudoValue
                }
                else -> collected[name] = value
            }
        }

        val resolvedHashCheck = hashCheck
        if (resolvedHashCheck.isNullOrBlank()) {
            return Result.failure(IllegalStateException("hash_check missing from reply form"))
        }

        // Sujet is part of the form contract but lives outside the hidden inputs in
        // some HFR renderings (it can be either visible or hidden depending on layout).
        val sujet = sujetInput?.attr("value").orEmpty()

        // Phase 2C (#146) — capture the textarea HFR uses to ship a quote prefill.
        // For a simple reply the textarea is empty ; for a quote HFR wraps the cited
        // post in a `[quotemsg=N,opaque,userId]...[/quotemsg]` block which we forward
        // verbatim (the `opaque` middle parameter is server-controlled, never recompute
        // it client-side). Jsoup's `.text()` would collapse whitespace and HTML-decode
        // entities — we deliberately use `wholeText()` so the user sees the raw BBCode
        // exactly as HFR composed it.
        val contentTextarea = replyForm.selectFirst("textarea[name=content_form]")
        val initialContent = contentTextarea?.wholeText().orEmpty()

        return Result.success(
            ReplyForm(
                hashCheck = resolvedHashCheck,
                sujet = sujet,
                hiddenFields = collected.toMap(),
                isAnonymous = isAnonymous,
                initialContent = initialContent,
            ),
        )
    }
}
