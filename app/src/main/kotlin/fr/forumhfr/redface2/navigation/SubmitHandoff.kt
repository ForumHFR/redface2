package fr.forumhfr.redface2.navigation

/**
 * #1301 — where the feedback of a SUCCESSFUL editor submit belongs, decided from the nav entry the
 * editor pop is about to reveal.
 *
 * Sol's inventory of the screens that can host an editor (2026-09-20) : `PostEditorRoute` over the
 * flags list (#15 « Poster un message »), `PostEditorRoute` over a `TopicRoute` (reply, escalation,
 * multi-quote, edit), `TopicFormRoute(New)` over a `CategoryRoute` (which has its own creation
 * Toast) and `TopicFormRoute(EditFirstPost)` over a `TopicRoute`. The only hole outside the topic is
 * therefore the flags list — which already owns a `SnackbarHostState`.
 */
internal enum class SubmitHandoff {
    /**
     * The revealed entry IS the topic that accepted the message : arm the #895 étape 4 outcome, a
     * deferred `TopicSubmitResult` the topic screen consumes to force-refresh and land on it.
     */
    TopicOutcome,

    /**
     * The revealed entry is the flags list : it has no topic ViewModel to hand a result to, so the
     * reader is owed a bounded acknowledgement — no refresh, no navigation. Publishing a
     * [TopicOutcome] here instead would leave it armed in the single slot until a LATER, unrelated
     * open of that topic consumed it (refresh + landing out of nowhere).
     */
    FlagsAcknowledgement,

    /** Anything else (another topic, a category, a deep-linked entry) gets no feedback at all. */
    None,
}

/**
 * #1301 — the [SubmitHandoff] owed by an editor that just submitted to `(cat, topicId)` and is
 * about to pop, revealing [below]. [topicId] is null for the editor modes that carry no topic, and
 * those can never own a topic outcome.
 */
internal fun submitHandoffFor(below: Any?, cat: Int, topicId: Int?): SubmitHandoff = when {
    topicId != null && isTopicEntryFor(below, cat, topicId) -> SubmitHandoff.TopicOutcome
    below is FlagsListRoute -> SubmitHandoff.FlagsAcknowledgement
    else -> SubmitHandoff.None
}
