package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.domain.error.HfrErrorKind
import fr.forumhfr.redface2.core.model.profile.Sanction

sealed interface SanctionsUiState {
    data object Loading : SanctionsUiState
    data object SignInRequired : SanctionsUiState
    data class Empty(val pseudo: String) : SanctionsUiState
    data class Loaded(val pseudo: String, val sanctions: List<Sanction>) : SanctionsUiState
    data class Error(val kind: HfrErrorKind) : SanctionsUiState
}

sealed interface SanctionsIntent {
    data object Retry : SanctionsIntent
}
