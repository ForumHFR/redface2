package fr.forumhfr.redface2.core.model.write

/**
 * Wire-side view of HFR's poll vote form (`form[method=post][action*=vote.php]`).
 *
 * The form is transient and belongs to the exact topic page from which it was parsed. In
 * particular, [hashCheck] is a volatile anti-CSRF token: it must never be persisted, copied to a
 * `SavedStateHandle`, or logged. A blank token is a valid parse result for a logged-out page; the
 * submit repository rejects it before issuing a request.
 *
 * @property hashCheck HFR's `hash_check`, kept verbatim. Authenticated forms carry a non-empty
 *   32-hex token; logged-out forms carry an empty value.
 * @property hiddenFields exact hidden `name` to `value` pairs in document order, excluding
 *   `hash_check` (surfaced separately). Live forms carry `cat`, `p`, `page`, `sondage`, `owntopic`,
 *   `subcat`, and `numeropost`.
 * @property choices selectable options in document order. Single-choice polls use radios named
 *   `reponse`; multiple-choice polls use checkboxes named `reponse1` through `reponseN`.
 * @property multipleChoice `true` for checkbox options, `false` for radio options.
 * @property maxSelections maximum number of choices parsed from « Sondage à N choix possibles ».
 *   A radio group resolves to `1`; `null` means that a multiple-choice limit is genuinely unknown.
 */
data class PollVoteForm(
    val hashCheck: String,
    val hiddenFields: Map<String, String>,
    val choices: List<PollVoteChoice>,
    val multipleChoice: Boolean,
    val maxSelections: Int?,
)

/**
 * One selectable option of a [PollVoteForm], captured verbatim from its `<input>` and bound label.
 *
 * @property id input `id` (for example `sond1`), used by the corresponding `<label for>`.
 * @property name POST field name: `reponse` for a radio, `reponseN` for a checkbox.
 * @property value POST value: the option index for a radio, `1` for a checkbox.
 * @property label human-readable option text, with HTML entities decoded by the parser.
 */
data class PollVoteChoice(
    val id: String,
    val name: String,
    val value: String,
    val label: String,
)
