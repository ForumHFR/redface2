package fr.forumhfr.redface2.core.model.write

/**
 * Parsed view of HFR's `message.php` reply form. Carries the volatile bits that the
 * subsequent `bddpost.php` POST has to echo back — chiefly the per-session CSRF
 * token `hash_check` — plus the static hidden fields HFR expects to receive
 * verbatim (`cat`, `subcat`, `post`, `page`, `verifrequet=1100`, `sujet`, `cache`,
 * `sond`, `owntopic`, `config`, `MsgIcon`, `signature`, `wysiwyg`, …).
 *
 * The model is intentionally a `Map<String, String>` rather than a typed bag: HFR
 * adds and removes hidden fields freely between deploys and we'd rather forward
 * them all than dropdown one and silently break the contract. Sensitive fields
 * (`password`, `pseudo` for an anonymous form) are filtered out at parse time —
 * see [ReplyForm.hiddenFields] — so the repository never re-posts them.
 *
 * [isAnonymous] is true when the parsed form exposes an editable `pseudo`/`password`
 * pair, which HFR serves whenever the session cookie is missing or expired. The
 * reply repository refuses to use such a form and surfaces a "login required" error
 * to the UI instead.
 */
data class ReplyForm(
    val hashCheck: String,
    val sujet: String,
    val hiddenFields: Map<String, String>,
    val isAnonymous: Boolean,
)
