package fr.forumhfr.redface2.core.domain.editor

/**
 * Builds the stable, content-free context key under which an editor's draft is cached (#405).
 * The key encodes ONLY routing identity (mode + ids) — never any message body, subject or
 * recipient. The owning account is prepended by the store, not here. `numreponse` is unique per
 * category, so edit keys carry `cat` to stay globally unique.
 *
 * The reply key intentionally does NOT carry the quoted `numreponse`: per #405 a quote is appended
 * to the restored draft, so the key must stay stable for a given topic regardless of which post is
 * being cited (otherwise each « Citer » would fork a separate draft).
 */
object EditorDraftKey {
    fun reply(cat: Int, topicId: Int): String = "reply:$cat:$topicId"
    fun newTopic(cat: Int): String = "newtopic:$cat"
    fun editPost(cat: Int, numreponse: Int): String = "edit:$cat:$numreponse"
    fun editFirstPost(cat: Int, numreponse: Int): String = "editfp:$cat:$numreponse"
    fun mpReply(threadId: Int): String = "mpreply:$threadId"

    // A function (not a const) for API symmetry with the sibling key builders above: there is a
    // single MP-compose draft at a time, so the key is fixed.
    @Suppress("FunctionOnlyReturningConstant")
    fun mpCompose(): String = "mpnew"
}
