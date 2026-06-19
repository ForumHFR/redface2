package fr.forumhfr.redface2.feature.settings

import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry

/**
 * #509 — state of the « Utilisateurs masqués » sub-page: the ordered list of blacklisted users and the
 * pseudo currently being typed in the add field.
 */
data class SettingsBlacklistState(
    val entries: List<BlacklistEntry> = emptyList(),
    val newPseudo: String = "",
) {
    /** The add button is enabled only when the field holds a non-blank pseudo. */
    val canAdd: Boolean get() = newPseudo.isNotBlank()
}

sealed interface SettingsBlacklistIntent {
    data class PseudoChanged(val pseudo: String) : SettingsBlacklistIntent
    data object AddClicked : SettingsBlacklistIntent
    data class RemoveClicked(val entry: BlacklistEntry) : SettingsBlacklistIntent
}
