package fr.forumhfr.redface2.core.model.write

/**
 * Outcome of a GET to `/user/addflag.php` (place a favourite on a precise topic
 * position, #986). HFR returns HTTP 200 in every case, so success vs failure is read
 * from the body text :
 *
 * - [Success] — the page carries « Favori positionné ».
 * - [Failure] — the page does **not** carry that sentence : the add was refused or HFR
 *   served an unexpected page.
 *
 * No raw body text is carried on [Failure] : the response can embed session metadata
 * and we never want it leaking into a log or snapshot. The caller surfaces a generic
 * « impossible de poser le favori » message and does **not** fabricate a cache row on
 * failure.
 */
sealed interface FlagAddResult {
    data object Success : FlagAddResult
    data object Failure : FlagAddResult
}
