package fr.forumhfr.redface2.core.model.write

/**
 * Parsed view of HFR's topic-level form (Phase 2D #148 edit first post,
 * Phase 2E #149 create topic).
 *
 * Like [ReplyForm] for the post-level surface, but with two structural
 * differences :
 *
 * - HFR's first-post and create-topic forms carry a writable
 *   `<input name="sujet">` ; the editor must surface it as a real text field,
 *   not a hidden echo.
 * - The form carries a `<select name="subcat">` instead of a single hidden
 *   `subcat` value. Subcategory choices are exposed to the UI so the user
 *   can re-categorise their topic or pick the category for a new topic. Edit FP
 *   starts from HFR's pre-selection ; create-topic may start from the entry chip
 *   because HFR often renders no selected `<option>`.
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
     * HFR user id parsed from the JS bootstrap call `find_smilies_timer('hfr.inc', N)`.
     * Used by the Phase 2F-B (#11 partial) wiki smiley picker to call the search endpoint
     * with the logged-in id. `null` for anonymous / unparseable forms (the repository falls
     * back to `user_id=0`).
     */
    val userId: Int? = null,
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
    /**
     * #213 — `true` when HFR served a `<select name="subcat">` on this form
     * (i.e. the category HAS sub-categories), `false` when it did not. The
     * « Intelligence artificielle » category (cat=32) has no sub-category, so
     * HFR ships the create-topic form WITHOUT any `<select name=subcat>` nor
     * `<input name=subcat>` ; the form is nonetheless valid and posts with
     * `subcat=0` (proven on the live `write_ia_create_form.html` capture).
     *
     * The create flow uses this flag to decide whether submit needs a
     * `selectedSubcat > 0` (`true`) or may post `subcat=0` straight away
     * (`false`). Edit FP never reaches this branch : it fail-fasts at parse
     * time when the select is missing, so the value is always `true` there.
     *
     * Defaults to `true` so the historical contract (a cat with sub-categories
     * requiring an explicit pick) holds for every existing construction site.
     */
    val hasSubcategorySelect: Boolean = true,
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
