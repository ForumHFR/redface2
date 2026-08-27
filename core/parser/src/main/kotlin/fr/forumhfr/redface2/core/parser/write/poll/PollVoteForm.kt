package fr.forumhfr.redface2.core.parser.write.poll

/**
 * #779 (PR 1) — wire-side view of HFR's poll VOTE form (`form[method=post][action*=vote.php]`),
 * parsed from a topic page for the future submit path (PR 2/PR 3). **Nothing submits it yet** —
 * there is no `HfrClient.submitPollVote`, no repository and no MVI slice in this PR; those are
 * blocked on an authenticated GET capture and a POST-response capture that do not exist. This model
 * is deliberately `internal` to `:core:parser`: it is preparation, not a public contract.
 *
 * @property hashCheck HFR's anti-CSRF token. **Empty on every capture we have**, because all poll
 *   fixtures are logged-out (`vote.php` rejects a blank token, so PR 2 cannot proceed until an
 *   authenticated capture supplies a non-empty one). An empty value is expected here and is NOT a
 *   parse failure — unlike the reply/topic forms, which fail-fast on a missing token.
 * @property hiddenFields the exact `input[type=hidden]` name→value pairs of the form, `hash_check`
 *   excluded (it lives in [hashCheck]). On the live captures this is `cat`, `p`, `page`, `sondage`,
 *   `owntopic`, `subcat`, `numeropost`. Forwarded verbatim on submit — never invented or reordered.
 * @property choices the vote options in document order. Single-choice polls use radios all named
 *   `reponse`; multiple-choice polls use one checkbox per option named `reponse1`..`reponseN`.
 * @property multipleChoice `true` when the options are checkboxes (multi), `false` for radios
 *   (mono) — detected from the input TYPE, the robust signal proven in #697.
 * @property maxSelections the vote cap from « Sondage à N choix possibles ». `1` for a single-choice
 *   poll (a radio group allows exactly one — factual, not invented). `null` only when the poll is
 *   multiple-choice AND the caption could not be read: the limit is then genuinely unknown and must
 *   not be coerced to a made-up number.
 */
internal data class PollVoteForm(
    val hashCheck: String,
    val hiddenFields: Map<String, String>,
    val choices: List<PollVoteChoice>,
    val multipleChoice: Boolean,
    val maxSelections: Int?,
)

/**
 * #779 (PR 1) — one selectable option of a [PollVoteForm], captured verbatim from its `<input>` and
 * the `<label>` bound to it.
 *
 * @property id the input's `id` attribute (e.g. `sond1`), the anchor the sibling `<label for>` uses.
 * @property name the input's `name` attribute: `reponse` for a single-choice radio, `reponseN` for a
 *   multiple-choice checkbox. This is the POST key the future submit sends.
 * @property value the input's `value` attribute: the 1-based option index for a radio, `1` for a
 *   checkbox. This is the POST value the future submit sends.
 * @property label the human-readable option text from the bound `<label>`, entities decoded and
 *   whitespace trimmed by Jsoup.
 */
internal data class PollVoteChoice(
    val id: String,
    val name: String,
    val value: String,
    val label: String,
)
