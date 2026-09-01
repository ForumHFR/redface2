package fr.forumhfr.redface2.core.model.write

/**
 * Result of one non-idempotent poll-closure request to HFR (`close_sondage.php`).
 *
 * The closure is **irreversible** : HFR offers no re-open endpoint. Only two live shapes matter to
 * the caller — HFR confirmed the closure ([Success]), or it did not ([Failure]). The failure shapes
 * (non-owner, already-closed, topic without a poll) were not captured live, so they collapse to a
 * single generic [Failure] rather than an asserted, unproven marker. Transport errors are raised as
 * exceptions by the repository and never surface as a [PollCloseResult].
 */
sealed interface PollCloseResult {
    data object Success : PollCloseResult
    data object Failure : PollCloseResult
}
