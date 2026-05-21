package fr.forumhfr.redface2.core.model.write

/**
 * Parsed view of HFR's topic-level form (Phase 2D #148 : edit first post).
 *
 * Like [ReplyForm] for the post-level surface, but with two structural
 * differences :
 *
 * - HFR's first-post form carries a writable `<input name="sujet">` ; the
 *   editor must surface it as a real text field, not a hidden echo.
 * - The form carries a `<select name="subcat">` instead of a single hidden
 *   `subcat` value. Subcategory choices are exposed to the UI so the user
 *   can re-categorise their topic, but the MVP only forwards whatever HFR
 *   pre-selected unless an explicit `SubcatSelected` intent flips it.
 *
 * Poll fields are captured separately into [TopicPollForm] so an UI that
 * does not edit polls can leave them alone while still preserving the
 * server-side state on POST (browser-style passthrough).
 *
 * Sensitive fields :
 * - `password` is filtered out unconditionally.
 * - `delete` is filtered out unconditionally — the FP form carries an
 *   « Effacer l'intégralité du sujet » checkbox that we never want to
 *   transmit accidentally on an edit (deletion is a separate, future flow).
 * - `hash_check` lives on the form but is never persisted in Compose state
 *   nor logged in `DiagnosticsLog`.
 */
data class TopicForm(
    val hashCheck: String,
    val subject: String,
    val initialContent: String,
    /**
     * Pre-selected sub-category from the parsed `<select>`. `null` is a valid
     * value in two cases : the « Aucune » option is the only `selected` (Edit FP
     * fail-safe path returns a `Result.failure` before reaching here), or HFR
     * served no `selected` attribute at all (the normal new-topic form shape —
     * cf. `parseNewTopic`). The submit code refuses to send `subcat=` empty :
     * the ViewModel must produce a real `Int > 0` from the dropdown choice
     * before calling the repository.
     */
    val selectedSubcat: Int?,
    val subcategoryChoices: List<TopicFormSubcategoryChoice>,
    val hiddenFields: Map<String, String>,
    val options: ReplyFormOptions,
    val msgIcon: String?,
    val poll: TopicPollForm,
    val isAnonymous: Boolean,
)

/**
 * One option of HFR's `<select name="subcat">`. `id` is `null` for the
 * « Aucune » option which we never want to submit (HFR would refuse). The
 * UI exposes the labels verbatim ; we never invent or translate them.
 */
data class TopicFormSubcategoryChoice(
    val id: Int?,
    val label: String,
    val selected: Boolean,
)

/**
 * Poll fields captured from the FP form. Phase 2D #148 does **not** mutate
 * poll fields — `editableInThisVersion` stays false until a fixture with an
 * existing active poll proves the contract. The MVP behaves like a browser
 * with the « Présence d'un sondage » checkbox left in its server-side state :
 * if `have_sondage` was checked we forward the existing answers and timing
 * fields as-is ; if it was unchecked we drop them (HFR's submit semantics).
 *
 * `fields` is the raw map of `name -> value` for every poll-related input
 * we captured — convenient to forward verbatim via `form.hiddenFields` at
 * POST time without having to repeat the allow-list logic in the repository.
 */
data class TopicPollForm(
    val present: Boolean,
    val fields: Map<String, String>,
    val editableInThisVersion: Boolean = false,
)
