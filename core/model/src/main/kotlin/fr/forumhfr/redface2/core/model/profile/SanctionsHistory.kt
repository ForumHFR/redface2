package fr.forumhfr.redface2.core.model.profile

sealed interface SanctionsHistory {
    data class Loaded(val pseudo: String, val sanctions: List<Sanction>) : SanctionsHistory

    data object SignInRequired : SanctionsHistory
}
