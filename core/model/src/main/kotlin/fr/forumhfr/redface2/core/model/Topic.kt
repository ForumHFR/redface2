package fr.forumhfr.redface2.core.model

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
)

data class PollOption(
    val text: String,
    val votes: Int,
    val percentage: Float,
)
