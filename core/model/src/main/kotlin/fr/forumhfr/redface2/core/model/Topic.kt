package fr.forumhfr.redface2.core.model

import fr.forumhfr.redface2.core.model.write.PollVoteForm

data class Topic(
    val cat: Int,
    val post: Int,
    /**
     * Sub-category id of POST, read from the `input[name=subcat]` of the reply form
     * (`form[action*=bddpost.php]`) when HFR rendered one (cf.
     * `docs/specs/protocol-hfr.md` § POST `bddpost.php`). Required by HFR's
     * `message.php` / `bddpost.php` write endpoints.
     *
     * `0` is a **valid, postable** value : HFR emits `subcat=0` for a category without
     * sub-category (e.g. cat=32 « Intelligence artificielle »), proven by a live
     * capture of the IA reply form (see protocol-hfr.md). Only [SUBCAT_UNKNOWN] (`-1`)
     * is blocking — it means no reply form was present (logged-out / prefetch anon row,
     * or a v3 cache that pre-dates subcat persistence) so the topic is read-only until a
     * live authenticated refresh produces a form. The sentinel is never transmitted to
     * HFR — write flows refuse `subcat < 0`.
     */
    val subcat: Int,
    val title: String,
    val posts: List<Post>,
    val page: Int,
    val totalPages: Int,
    val isFirstPostOwner: Boolean,
    val poll: Poll?,
    /**
     * Vote form associated with [poll], or `null` when HFR served results/no poll.
     *
     * This capability is **transient** and tied to the exact live page: its `hash_check` is never
     * persisted, copied to `SavedStateHandle`, or logged. Room deliberately stores only [poll], so
     * cache rehydration restores this field as `null` until an authenticated GET supplies a fresh
     * form. Logged-out parsing may still surface a form with a blank token; the submit repository
     * owns the downstream guard that rejects it before POST.
     */
    val pollVoteForm: PollVoteForm? = null,
    /**
     * Whether the user can reply to / quote / edit on this topic. Postability is
     * **driven by the presence of the `bddpost` reply form** in the topic page HTML
     * (#213). HFR renders that form only in an authenticated session on a non-locked
     * topic, so `canReply=true` implies a usable [subcat] (>= 0, possibly the IA `0`).
     *
     * Defaults to `false` : a topic built without an observed reply form — cache rows
     * read before this column existed, anonymous prefetch rows, or any model constructed
     * outside [fr.forumhfr.redface2.core.parser] — is read-only until a live
     * authenticated refresh surfaces the form. The previous `hasSubcat` heuristic
     * (`subcat > 0`) is replaced by this field : it wrongly excluded postable cat-IA
     * topics (`subcat=0`) and wrongly trusted the search-widget subcat captured
     * logged-out. See `docs/specs/protocol-hfr.md` § POST `bddpost.php`.
     */
    val canReply: Boolean = false,
    /**
     * Chantier C (#546) — the intra-topic search form (`transsearch.php`) hidden fields parsed from
     * THIS page, or `null` when the page carried no usable search form. It is **transient, never
     * persisted** : the cache mapper reconstructs it as `null`, so a cached row keeps the
     * intra-topic-search affordance disabled until a live authenticated re-fetch surfaces a usable
     * form ([TopicSearchForm.canSearch] additionally requires a non-empty `hash_check`). Coupling it
     * to the parsed page keeps the form in lockstep with the `(cat, post, page)` it belongs to.
     */
    val searchForm: TopicSearchForm? = null,
) {
    companion object {
        const val SUBCAT_UNKNOWN: Int = -1
    }
}

data class Poll(
    val question: String,
    val options: List<PollOption>,
    val multipleChoice: Boolean,
    val totalVotes: Int,
    val hasVoted: Boolean,
    /**
     * #697 — `false` when HFR served the poll's FORM shape (radio/checkbox inputs, the shape every
     * not-yet-voted — and thus every anonymous — fetch gets): options carry no votes/percentages
     * (their numeric fields are 0 and meaningless), only the question and labels are known.
     * `true` on the RESULTS shape (.sondageLeft bars), the only shape parsed before #697.
     */
    val resultsAvailable: Boolean = true,
    /**
     * #779 (PR 1) — the maximum number of options a voter may pick, read from HFR's
     * « Sondage à N choix possibles » caption (present on both the vote FORM's `div.sondage`
     * and the results card). A single-choice poll (radio inputs / no caption) resolves to `1`,
     * a factual property of a radio group — not an invented value.
     *
     * `null` means the limit is genuinely unknown : a legacy cache row written before this field
     * existed, or a multiple-choice poll whose caption could not be read. Callers must treat
     * `null` as « unknown legacy limit », never silently coerce it to `1` — that would falsely
     * cap a multi-choice poll at a single vote. HFR's real vote endpoint stays untouched here
     * (#779 PR 1 is parse-only) ; the future submit path (PR 2/PR 3) consumes this.
    */
    val maxSelections: Int? = null,
    val closed: Boolean = false,
    /**
     * Whether HFR exposed the native adjacent `close_sondage.php` link on this page. The link is
     * rendered only for the owner of an open poll, so this server-provided capability is the sole
     * gate for the close affordance. `false` also covers non-owner, closed and legacy cache rows.
     */
    val canClose: Boolean = false,
    /**
     * HFR wall-clock expiry without a time zone. Never convert it to an `Instant` or infer closure
     * from the local clock: the server-provided [closed] state is the source of truth.
     */
    val expiresAt: java.time.LocalDateTime? = null,
    /** `0` is a real count; `null` means no results counter or a legacy cache row. */
    val blankVotes: Int? = null,
)

data class PollOption(
    val text: String,
    /** #697 — meaningless (0) when the owning [Poll.resultsAvailable] is `false`. */
    val votes: Int,
    /** #697 — meaningless (0) when the owning [Poll.resultsAvailable] is `false`. */
    val percentage: Float,
)
