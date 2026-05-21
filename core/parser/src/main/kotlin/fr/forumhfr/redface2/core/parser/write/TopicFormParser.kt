package fr.forumhfr.redface2.core.parser.write

import fr.forumhfr.redface2.core.model.write.ReplyFormOptions
import fr.forumhfr.redface2.core.model.write.TopicForm
import fr.forumhfr.redface2.core.model.write.TopicFormSubcategoryChoice
import fr.forumhfr.redface2.core.model.write.TopicPollForm
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parses HFR's topic-level form — the one served by `message.php?…&numreponse=N`
 * when `N` happens to be a topic's first post. Wire-side this is the same page
 * served for a regular edit (`<form action="bdd.php?config=hfr.inc">`) but it
 * carries three extras :
 *
 * - a writable `<input name="sujet">` (the topic title),
 * - a `<select name="subcat">` (subcategory),
 * - poll fields (`have_sondage`, `textreponse0..10`, `allowvisitor`, `max_votes`,
 *   `jour`/`mois`/`annee`/`heure`/`minute`).
 *
 * The parser is a sibling of [ReplyFormParser] rather than a subclass — they
 * share the same hidden-input collection semantics (browser-style `checked`
 * filter for radios / checkboxes, deny `password`) but produce different
 * domain shapes ; pulling them under one common base would conflate the
 * post-level and topic-level contracts the way Phase 2C-A explicitly avoided.
 *
 * Sensitive fields :
 * - `password` is filtered out unconditionally.
 * - `delete` is filtered out unconditionally — the FP form labels its `delete`
 *   checkbox « Effacer l'intégralité du sujet ». Phase 2D #148 never deletes.
 * - `hash_check` is extracted into [TopicForm.hashCheck] but is never logged
 *   nor surfaced to Compose state by the caller.
 */
class TopicFormParser {

    @Suppress("ReturnCount") // Two failure guards + the success return.
    fun parse(html: String): Result<TopicForm> {
        val document = Jsoup.parse(html)
        // HFR's topic-level form lives in the same `bdd.php` action as a
        // regular post edit ; we anchor on the action attribute and let the
        // caller decide (via the URL it hit) whether this is a FP edit or a
        // regular edit.
        val form = document.selectFirst("form[action*=bdd.php]")
            ?: return Result.failure(IllegalStateException("Topic form not found"))

        val pseudoInput = form.selectFirst("input[name=pseudo]")
        val pseudoValue = pseudoInput?.attr("value").orEmpty()
        val isAnonymous = pseudoInput != null && pseudoValue.isEmpty()

        val collection = collectInputs(form, isAnonymous, pseudoValue)
        val hashCheck = collection.hashCheck
        if (hashCheck.isNullOrBlank()) {
            return Result.failure(IllegalStateException("hash_check missing from topic form"))
        }

        val subject = form.selectFirst("input[name=sujet]")?.attr("value").orEmpty()
        // `wholeText()` preserves the BBCode and line breaks HFR rendered ; `text()`
        // would collapse whitespace and HTML-decode entities, breaking the round-trip.
        val initialContent = form.selectFirst("textarea[name=content_form]")?.wholeText().orEmpty()

        val (selectedSubcat, subcategoryChoices) = parseSubcategories(form)
        val options = ReplyFormOptions(
            signatureEnabled = form.optionCheckbox("signature"),
            smileyDisabled = form.optionCheckbox("smiley"),
            emailNotificationEnabled = form.optionCheckbox("emaill"),
        )
        val msgIcon = form.selectFirst("input[type=radio][name=MsgIcon][checked]")
            ?.attr("value")
            ?.takeIf { it.isNotEmpty() }
        val poll = parsePoll(form)

        return Result.success(
            TopicForm(
                hashCheck = hashCheck,
                subject = subject,
                initialContent = initialContent,
                selectedSubcat = selectedSubcat,
                subcategoryChoices = subcategoryChoices,
                hiddenFields = collection.fields,
                options = options,
                msgIcon = msgIcon,
                poll = poll,
                isAnonymous = isAnonymous,
            ),
        )
    }

    /**
     * Walks every `<input name="…">` under the form and applies the
     * browser-style submit semantics : radios / checkboxes are only collected
     * when `checked`, `password` and `delete` are dropped, anonymous `pseudo`
     * is dropped. Identical to [ReplyFormParser.collectInputs] in spirit but
     * with the additional `delete` deny rule because the FP form ships the
     * destructive « Effacer l'intégralité du sujet » checkbox.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // Single-pass collector ; splitting hurts readability.
    private fun collectInputs(
        form: Element,
        isAnonymous: Boolean,
        pseudoValue: String,
    ): CollectedInputs {
        val collected = mutableMapOf<String, String>()
        var hashCheck: String? = null
        form.select("input[name]").forEach { input ->
            val name = input.attr("name")
            if (name.isEmpty()) return@forEach
            val type = input.attr("type").lowercase()
            // Hard deny : password and delete must never round-trip. The
            // `delete` filter is the topic-level addition — without it, an
            // accidental refetch with the checkbox already checked server-side
            // would propagate « Effacer l'intégralité du sujet ».
            if (type == "password" || name == "password" || name == "delete") return@forEach
            // Browser submit semantics for radios / checkboxes.
            if ((type == "radio" || type == "checkbox") && !input.hasAttr("checked")) {
                return@forEach
            }
            val value = input.attr("value")
            when (name) {
                "hash_check" -> {
                    if (value.isNotEmpty()) hashCheck = value
                }
                "pseudo" -> {
                    if (!isAnonymous && pseudoValue.isNotEmpty()) collected[name] = pseudoValue
                }
                else -> collected[name] = value
            }
        }
        return CollectedInputs(fields = collected.toMap(), hashCheck = hashCheck)
    }

    /**
     * Parses the `<select name="subcat">` block : returns the currently
     * selected id and every available choice. The « Aucune » option (no
     * `value=""`) is rendered as `id = null` and we never submit it — the
     * domain requires `subcat > 0`.
     */
    private fun parseSubcategories(form: Element): Pair<Int, List<TopicFormSubcategoryChoice>> {
        val select = form.selectFirst("select[name=subcat]")
            ?: return 0 to emptyList()
        val options = select.select("option")
        val choices = options.map { option ->
            val raw = option.attr("value")
            val id = raw.toIntOrNull()?.takeIf { it > 0 }
            TopicFormSubcategoryChoice(
                id = id,
                label = option.text().trim(),
                selected = option.hasAttr("selected"),
            )
        }
        val selectedSubcat = choices.firstOrNull { it.selected }?.id
            ?: choices.firstOrNull { it.id != null }?.id
            ?: 0
        return selectedSubcat to choices
    }

    /**
     * Captures poll fields verbatim. Phase 2D #148 keeps the poll read-only :
     * `editableInThisVersion = false`. The captured `fields` map mirrors
     * exactly what a browser would submit (only the boxes/radios HFR pre-
     * checked + the textreponse values + the max_votes select option HFR
     * pre-selected, if any).
     */
    private fun parsePoll(form: Element): TopicPollForm {
        val haveSondage = form.selectFirst("input[type=checkbox][name=have_sondage]")
            ?.hasAttr("checked") == true
        val fields = mutableMapOf<String, String>()
        // textreponse0..10 — captured verbatim with their current value (which
        // may be empty). We only forward non-empty values so an empty fixture
        // does not pollute the POST body.
        for (idx in 0..MAX_POLL_OPTION) {
            val input = form.selectFirst("input[name=textreponse$idx]") ?: continue
            val value = input.attr("value")
            if (value.isNotEmpty()) fields["textreponse$idx"] = value
        }
        // allowvisitor : browser-style checkbox.
        form.selectFirst("input[type=checkbox][name=allowvisitor]")?.let { input ->
            if (input.hasAttr("checked")) fields["allowvisitor"] = input.attr("value")
        }
        // max_votes : capture the option HFR pre-selected, if any. When no
        // option has `selected`, HFR's default at submit time is the first
        // option ; we forward nothing so the server picks its own default.
        form.selectFirst("select[name=max_votes] option[selected]")?.let { option ->
            val value = option.attr("value")
            if (value.isNotEmpty()) fields["max_votes"] = value
        }
        // Timing fields (jour / mois / annee / heure / minute). Forward as-is
        // when HFR pre-filled them, otherwise leave them out.
        listOf("jour", "mois", "annee", "heure", "minute").forEach { name ->
            val input = form.selectFirst("input[name=$name]") ?: return@forEach
            val value = input.attr("value")
            if (value.isNotEmpty()) fields[name] = value
        }
        return TopicPollForm(
            present = haveSondage,
            fields = fields.toMap(),
            editableInThisVersion = false,
        )
    }

    private fun Element.optionCheckbox(name: String): Boolean =
        selectFirst("input[type=checkbox][name=$name]")?.hasAttr("checked") == true

    private data class CollectedInputs(
        val fields: Map<String, String>,
        val hashCheck: String?,
    )

    private companion object {
        private const val MAX_POLL_OPTION = 10
    }
}
