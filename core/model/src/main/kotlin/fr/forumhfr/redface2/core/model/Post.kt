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
     * `ref` tracks the post position inside the current topic page but the
     * exact contract is not documented. `null` means HFR did not expose a
     * quote action for this post (locked topic, hidden post, anonymous read,
     * future server-side change). The UI hides the « Citer » action when this
     * field is `null` rather than guessing a default — see `protocol-hfr.md`
     * § Quote, "ref is opaque, never hardcode".
     *
     * Not persisted via Room : the value is cheap to re-derive from the topic
     * page HTML on every refresh, so we keep it transient on the in-memory
     * model and avoid a v4 → v5 schema bump for a value that is only useful
     * inside the same live session.
     */
    val quoteRef: Int? = null,
)
