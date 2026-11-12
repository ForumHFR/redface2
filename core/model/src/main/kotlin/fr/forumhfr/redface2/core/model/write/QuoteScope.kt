package fr.forumhfr.redface2.core.model.write

/**
 * Target that owns one transient quote selection.
 *
 * HFR topics use a numeric category while private-message conversations live under the string
 * `cat=prive`; modelling both as `(cat: Int, post: Int)` would make the private scope impossible
 * to represent honestly. The scope also completes a quoted message's identity: `numreponse` is
 * unique only inside an HFR category, never forum-wide.
 *
 * Deliberately not serializable. The scoped quote basket and card selections remain transient,
 * outside navigation routes, persistence and diagnostics. Durable inline requests and delivery
 * provenance encode their own scope/coordinates through the editor store's versioned codecs.
 */
sealed interface QuoteScope {

    /** One public topic, identified by its numeric HFR category and topic id. */
    data class Topic(val cat: Int, val topicId: Int) : QuoteScope

    /** One `cat=prive` conversation. */
    data class PrivateMessage(val threadId: Int) : QuoteScope
}
