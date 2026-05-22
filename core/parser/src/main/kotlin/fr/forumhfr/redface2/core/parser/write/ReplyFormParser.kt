package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyForm
import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.parser.smiley.SmileyUserIdExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses HFR's `message.php` reply / edit form. The page returned by HFR
 * contains many `<form>` elements (search box, navigation widgets, …) ; the
 * write form is the one whose `action` ends with `bddpost.php` (reply / quote /
 * create topic, Phase 2C #145+#146) **or** `bdd.php` (edit post, Phase 2D
 * #147). See fixtures
 * `core/parser/src/test/resources/fixtures/write_reply_form_open_topic.html`
 * and `…/write_edit_form_test_post.html`.
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

    @Suppress("ReturnCount") // Two failure guards + the success return — splitting hurts readability.
    fun parse(html: String): Result<ReplyForm> {
        val document = Jsoup.parse(html)
        // HFR ships the reply / quote / create-topic forms with `action="…
        // bddpost.php"` and the edit-post form with `action="…bdd.php"`. The
        // shapes of the two forms (hidden fields, options, MsgIcon, textarea)
        // are otherwise identical, so a single parser handles both. We bias
        // toward `bddpost.php` first because four out of five write flows use
        // it ; the `bdd.php` fallback is only reached on an edit form.
        val replyForm = document.selectFirst("form[action*=bddpost.php]")
            ?: document.selectFirst("form[action*=bdd.php]")
            ?: return Result.failure(IllegalStateException("Reply form not found"))

        val pseudoInput = replyForm.selectFirst("input[name=pseudo]")
        val pseudoValue = pseudoInput?.attr("value").orEmpty()
        val isAnonymous = pseudoInput != null && pseudoValue.isEmpty()

        val collection = collectInputs(replyForm, isAnonymous, pseudoValue)
        val resolvedHashCheck = collection.hashCheck
        if (resolvedHashCheck.isNullOrBlank()) {
            return Result.failure(IllegalStateException("hash_check missing from reply form"))
        }

        // Sujet is part of the form contract but lives outside the hidden inputs in
        // some HFR renderings (it can be either visible or hidden depending on layout).
        val sujet = replyForm.selectFirst("input[name=sujet]")?.attr("value").orEmpty()

        // Phase 2C (#146) — capture the textarea HFR uses to ship a quote prefill.
        // For a simple reply the textarea is empty ; for a quote HFR wraps the cited
        // post in a `[quotemsg=N,opaque,userId]...[/quotemsg]` block which we forward
        // verbatim (the `opaque` middle parameter is server-controlled, never recompute
        // it client-side). Jsoup's `.text()` would collapse whitespace and HTML-decode
        // entities — we deliberately use `wholeText()` so the user sees the raw BBCode
        // exactly as HFR composed it.
        val initialContent = replyForm.selectFirst("textarea[name=content_form]")
            ?.wholeText()
            .orEmpty()

        return Result.success(
            ReplyForm(
                hashCheck = resolvedHashCheck,
                sujet = sujet,
                hiddenFields = collection.fields,
                isAnonymous = isAnonymous,
                initialContent = initialContent,
                options = parseOptions(replyForm),
                msgIcon = parseMsgIcon(replyForm),
                userId = SmileyUserIdExtractor.extract(html),
            ),
        )
    }

    /**
     * Iterates every input under the reply form and applies the deny/allow rules
     * documented in the class header. Returns the collected `hiddenFields` map +
     * the resolved `hash_check`. The two are bundled so we only walk the DOM once.
     *
     * Restricting the initial selector to `input[type=hidden]` would silently drop
     * the user-typed pseudo field (which HFR renders as `type=text` even when the
     * user is authenticated) and would not protect against password input either
     * (which has `type=password`, not hidden) — both deny rules require seeing
     * every input on the form.
     */
    private fun collectInputs(
        replyForm: Element,
        isAnonymous: Boolean,
        pseudoValue: String,
    ): CollectedInputs {
        val collected = mutableMapOf<String, String>()
        var hashCheck: String? = null
        replyForm.select("input[name]").forEach { input ->
            val name = input.attr("name")
            if (name.isEmpty()) return@forEach
            val type = input.attr("type").lowercase()
            // Hard deny: password is never echoed back to HFR (HFR's hidden composer
            // fallback would re-authenticate over an existing cookie — never the path
            // we want). The `password` *name* check is belt-and-braces in case HFR
            // ever ships a non-`type=password` element with that name.
            if (type == "password" || name == "password") return@forEach
            // Radios and checkboxes follow browser semantics : a browser only submits
            // them when `checked`. Without this guard we would (a) overwrite the
            // chosen `MsgIcon` with whichever radio appears last in the DOM
            // (`MsgIcon=16` = `:heink:` instead of the default icon 1), and (b)
            // silently transmit every option checkbox (`signature`, `smiley`,
            // `emaill`) regardless of the user's intent. The check is structural
            // so any future HFR-added toggle stays inert until explicitly opted-in.
            if ((type == "radio" || type == "checkbox") && !input.hasAttr("checked")) {
                return@forEach
            }
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
        return CollectedInputs(fields = collected.toMap(), hashCheck = hashCheck)
    }

    /**
     * HFR per-post options : three independent checkboxes whose `checked`
     * attribute carries the server-side default for this user / topic. We surface
     * them on `ReplyForm.options` so the editor can seed its own toggle state
     * from the same defaults the HFR web composer would show.
     */
    private fun parseOptions(replyForm: Element): ReplyFormOptions = ReplyFormOptions(
        signatureEnabled = replyForm.optionCheckbox("signature"),
        smileyDisabled = replyForm.optionCheckbox("smiley"),
        emailNotificationEnabled = replyForm.optionCheckbox("emaill"),
    )

    private fun Element.optionCheckbox(name: String): Boolean =
        selectFirst("input[type=checkbox][name=$name]")?.hasAttr("checked") == true

    /**
     * `MsgIcon` is HFR's icon picker rendered as a row of radio inputs. The
     * server pre-checks the user's default ; we forward whichever radio is
     * `checked` instead of trusting iteration order. The value also lands in
     * `hiddenFields` (via [collectInputs]) ; we keep a dedicated field for
     * diagnostic clarity and for a future Phase 2D icon picker UI.
     */
    private fun parseMsgIcon(replyForm: Element): String? = replyForm
        .selectFirst("input[type=radio][name=MsgIcon][checked]")
        ?.attr("value")
        ?.takeIf { it.isNotEmpty() }

    private data class CollectedInputs(
        val fields: Map<String, String>,
        val hashCheck: String?,
    )
}
