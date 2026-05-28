package fr.forumhfr.redface2.core.model.write

/**
 * Outcome of a GET to `/user/delflag.php` (remove a single drapeau, #99). HFR returns
 * HTTP 200 in every case, so success vs failure is read from the body text :
 *
 * - [Success] — the page carries « Drapeau effacé avec succès » (cf. fixture
 *   `flag_delete_success.html`).
 * - [Failure] — the page does **not** carry that sentence : the drapeau was already
 *   gone, the deletion was refused, or HFR served an unexpected page (cf. fixture
 *   `flag_delete_already_removed.html`, which shows « Aucun favori n'est repertorié »).
 *
 * No raw body text is carried on [Failure] : the response can embed session metadata
 * and we never want it leaking into a log or snapshot. The UI surfaces a generic
 * « impossible de retirer le drapeau » message and, critically, does **not** mutate
 * any cache on failure.
 */
sealed interface FlagDeleteResult {
    data object Success : FlagDeleteResult
    data object Failure : FlagDeleteResult
}
