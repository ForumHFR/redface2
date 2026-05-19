package fr.forumhfr.redface2.core.model

import java.time.Instant

data class Post(
    val numreponse: Int,
    val author: String,
    val date: Instant,
    val content: PostContent,
    val avatarUrl: String?,
    val isEditable: Boolean,
    val isOwnPost: Boolean,
    val quotedAuthors: List<String>,
    val postIndex: Int?,
    /**
     * `ref` parameter parsed from HFR's quote link href
     * (`/message.php?...&numrep={numreponse}&ref={N}&...`). Phase 2C (#146) :
     * needed to build the quote GET URL. The value is opaque to the app —
     * `ref` correlates with the post position inside the current topic page,
     * but the exact contract is undocumented (cf. `docs/specs/protocol-hfr.md`
     * § Quote). The app forwards whatever HFR shipped, never re-derives it.
     * `null` means HFR did not expose a quote action for this post (locked
     * topic, hidden post, anonymous read, future server-side change). The UI
     * hides the « Citer » action in that case rather than guessing a default.
     *
     * Persisted in Room v5 (cf. `MIGRATION_4_5`) so cache hits keep the
     * « Citer » button available without a network refresh. Pre-v5 rows
     * backfill to `NULL` and recover the real value on the next live fetch.
     */
    val quoteRef: Int? = null,
)
