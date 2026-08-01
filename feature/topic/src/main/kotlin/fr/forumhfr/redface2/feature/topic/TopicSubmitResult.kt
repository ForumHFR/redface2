package fr.forumhfr.redface2.feature.topic

/**
 * #895 étape 4 (PR 2) — outcome of a successful FULL-EDITOR submit (reply / quote / edit /
 * edit-FP), published by `:app` BEFORE popping the editor and consumed exactly once by the topic
 * screen below, which forwards it to [TopicViewModel.applySubmitResult]. Replaces the historical
 * route-replace + `submitSignal` rebuild : the retained ViewModel now refreshes in place.
 *
 * The quick-reply sheet never goes through this type — it lives inside the topic screen and calls
 * `applySubmitResult` directly.
 *
 * @property eventId strictly-monotonic id from the `:app` holder ; keys the consumption
 *   `LaunchedEffect` so two rapid submits with identical `(targetPage, scrollTo)` both apply.
 * @property targetPage page parsed from HFR's success URL, or `null` when it could not be
 *   extracted — the ViewModel then falls back on its CANONICAL current page.
 * @property scrollTo `numreponse` parsed from the `#t{N}` success-URL fragment (quote / edit), or
 *   `null` when HFR anchored `#bas` (plain reply → bottom landing).
 */
data class TopicSubmitResult(
    val eventId: Long,
    val targetPage: Int?,
    val scrollTo: Int?,
)
