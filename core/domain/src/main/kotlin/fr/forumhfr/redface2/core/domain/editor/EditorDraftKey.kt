package fr.forumhfr.redface2.core.domain.editor

/**
 * Builds the stable, content-free context key under which an editor's draft is cached (#405).
 * Composition keys encode routing identity (mode + ids), never any raw message body, subject or
 * recipient. The owning account is prepended
 * by the store, not here. `numreponse` is unique per category, so edit keys carry `cat` to stay globally unique.
 *
 * The reply key intentionally does NOT carry the quoted `numreponse`: per #405 a quote is appended
 * to the restored draft, so the key must stay stable for a given topic regardless of which post is
 * being cited (otherwise each « Citer » would fork a separate draft).
 */
object EditorDraftKey {
    private const val REPORT_KEY = "report"
    private const val MP_COMPOSE_KEY = "mpnew"

    fun reply(cat: Int, topicId: Int): String = "reply:$cat:$topicId"
    fun newTopic(cat: Int): String = "newtopic:$cat"
    fun editPost(cat: Int, numreponse: Int): String = "edit:$cat:$numreponse"
    fun editFirstPost(cat: Int, numreponse: Int): String = "editfp:$cat:$numreponse"
    fun mpReply(threadId: Int): String = "mpreply:$threadId"

    fun report(): String = REPORT_KEY

    // A function (not a const) for API symmetry with the sibling key builders above: there is a
    // single MP-compose draft at a time, so the key is fixed.
    @Suppress("FunctionOnlyReturningConstant")
    fun mpCompose(): String = MP_COMPOSE_KEY

    /** Replies to a topic or to a private conversation: the contexts that receive quotes. */
    fun isReply(key: String): Boolean = key.startsWith("reply:") || key.startsWith("mpreply:")

    /**
     * ED-121: compositions with headers of their own (title, recipients). A header typed there is
     * content worth keeping and offering, even over a blank body.
     */
    fun composesHeaders(key: String): Boolean = key.startsWith("newtopic:") || key == MP_COMPOSE_KEY

    /**
     * ED-121: contexts whose replaced draft D stays offered instead of being overwritten by C.
     * Editing an existing message is not covered: its row competes with a server seed.
     */
    fun keepsOfferedDrafts(key: String): Boolean = isReply(key) || composesHeaders(key)
}
