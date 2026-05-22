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
    /**
     * Initial value HFR prefills inside `<textarea name="content_form">`. For a
     * reply-simple form it is empty ; for a quote form HFR ships the BBCode
     * `[quotemsg=N,opaque,userId]...[/quotemsg]` block already wrapped, with
     * the cited content rendered as the user would see it on the topic page.
     *
     * Redface 2 must reuse this prefill verbatim rather than reconstructing it
     * client-side: the second parameter (`opaque` above) is a server-controlled
     * positional id whose exact computation is undocumented (cf. `protocol-hfr.md`
     * § Quote). Surface this value to the editor so the user can edit-around the
     * quote block, but never tamper with the block itself before POST.
     */
    val initialContent: String = "",
    /**
     * Initial state of the three per-post option checkboxes HFR exposes (Activer
     * signature, Désactiver smileys, Activer notification par email). Read from
     * the `checked` attribute of each `<input type="checkbox">` so we mirror the
     * server-side preference for this user / this topic.
     *
     * Lifecycle :
     *  1. `ReplyFormParser` extracts these defaults from the HFR HTML.
     *  2. `PostEditorViewModel` seeds the editor toggles from `ReplyForm.options`
     *     on the first form load and flips `optionsHydratedFromForm` true.
     *  3. The user may toggle any of the three switches via the
     *     `ToggleSignature` / `ToggleSmileyDisabled` / `ToggleEmailNotification`
     *     intents — these mutate the editor state, not this field.
     *  4. At submit time, the VM hands the **final** editor choice to
     *     `ReplyRepository.submitReply(...)` through its `options` parameter.
     *
     * The repository never re-reads `ReplyForm.options` at POST time. This field
     * is solely the « defaults from HFR » seed for hydration, never a source of
     * truth for the wire shape.
     */
    val options: ReplyFormOptions = ReplyFormOptions(),
    /**
     * `MsgIcon` HFR radio value (the « ton du message » row). HFR renders ~16
     * icons ; the one with `checked="checked"` is the server-side default for
     * the user. We forward this value verbatim on POST without exposing it to
     * the UI for Phase 2C — Edit / Phase 2D may add a picker if there is real
     * user demand. `null` keeps HFR's own server-side default (the field is
     * simply not transmitted) ; in practice every authenticated form ships a
     * checked icon (typically `1`).
     */
    val msgIcon: String? = null,
    /**
     * HFR user id parsed from the JS bootstrap call `find_smilies_timer('hfr.inc', N)`
     * embedded in the form HTML. Used by the Phase 2F-B (#11 partial) wiki smiley picker
     * to call `GET /message-smi-mp-aj.php?config=hfr.inc&user_id=N&findsmilies=…` with the
     * logged-in id, so HFR pages the user's favourite perso first. `null` on anonymous /
     * unparseable forms — the repository falls back to `user_id=0`.
     */
    val userId: Int? = null,
)

/**
 * Per-post options HFR exposes as three checkboxes on the reply form. Captured
 * from the `checked` attribute server-side, then surfaced to the editor so the
 * user can flip them per post. The wire field names are HFR's own:
 *
 * - `signature` (« Activer votre signature » ; HFR default = on for most users)
 * - `smiley` (« Désactiver les smilies » ; off = smileys rendered as usual)
 * - `emaill` (« Activer la notification par email du sujet » ; off = no email)
 *
 * The repository only adds the corresponding form field to the POST when the
 * boolean is `true`, mirroring how a browser would submit an unchecked
 * checkbox (i.e. by omitting it entirely).
 */
data class ReplyFormOptions(
    val signatureEnabled: Boolean = false,
    /**
     * **Inverted semantics warning** : `true` means the user wants HFR to render
     * BBCode smiley shortcodes as plain text (the « Désactiver les smilies » HFR
     * checkbox is ON). It does **NOT** mean « smileys are active ». Wire mapping :
     * `smileyDisabled = true` → POST `smiley=1` → HFR strips smileys. This is the
     * only one of the three options where ON = privation ; future code reading
     * this field must keep the name's literal meaning to avoid silent inversion.
     */
    val smileyDisabled: Boolean = false,
    val emailNotificationEnabled: Boolean = false,
)
