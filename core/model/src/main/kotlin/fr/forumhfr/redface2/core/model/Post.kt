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
     * Not persisted via Room : the value is cheap to re-derive from the topic
     * page HTML on every refresh, so we keep it transient on the in-memory
     * model and avoid a v4 → v5 schema bump for a value that is only useful
     * inside the same live session. **Process-death consequence**: if the OS
     * kills the process and the user returns to the topic via the restored
     * back stack, posts loaded from the Room cache will all have
     * `quoteRef = null` and the « Citer » buttons stay hidden until the next
     * live refresh of the topic page replays the parser.
     */
    val quoteRef: Int? = null,
)
