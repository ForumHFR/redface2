package fr.forumhfr.redface2.core.model.write

/**
 * Target that owns one transient quote selection.
 *
 * HFR topics use a numeric category while private-message conversations live under the string
 * `cat=prive`; modelling both as `(cat: Int, post: Int)` would make the private scope impossible
 * to represent honestly. The scope also completes a quoted message's identity: `numreponse` is
 * unique only inside an HFR category, never forum-wide.
 *
 * Deliberately not serializable. Quote scopes and selections are handed over in memory and are
 * dropped on process death; the scoped quote basket must not be embedded in navigation routes or
 * persistence, nor emitted to diagnostics.
 */
sealed interface QuoteScope {

    /** One public topic, identified by its numeric HFR category and topic id. */
    data class Topic(val cat: Int, val topicId: Int) : QuoteScope

    /** One `cat=prive` conversation. */
    data class PrivateMessage(val threadId: Int) : QuoteScope
}
