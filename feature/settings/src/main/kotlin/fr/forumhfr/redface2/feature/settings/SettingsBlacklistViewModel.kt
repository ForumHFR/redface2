package fr.forumhfr.redface2.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.blacklist.BlacklistRepository
import fr.forumhfr.redface2.core.model.blacklist.BlacklistEntry
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * #509 — ViewModel for the « Utilisateurs masqués » sub-page. Mirrors the live blacklist from
 * [BlacklistRepository.observeEntries] and exposes add / remove actions. Add reuses the repository's
 * canonicalisation + first-wins dedup; remove targets the stored [BlacklistEntry.canonical].
 */
@HiltViewModel
class SettingsBlacklistViewModel @Inject constructor(
    private val blacklistRepository: BlacklistRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsBlacklistState())
    val state: StateFlow<SettingsBlacklistState> = _state.asStateFlow()

    init {
        blacklistRepository.observeEntries()
            .onEach { entries -> _state.update { it.copy(entries = entries) } }
            .launchIn(viewModelScope)
    }

    fun submit(intent: SettingsBlacklistIntent) {
        when (intent) {
            is SettingsBlacklistIntent.PseudoChanged ->
                _state.update { it.copy(newPseudo = intent.pseudo) }
            SettingsBlacklistIntent.AddClicked -> addCurrentPseudo()
            is SettingsBlacklistIntent.RemoveClicked ->
                viewModelScope.launch { blacklistRepository.unblock(intent.entry.canonical) }
        }
    }

    private fun addCurrentPseudo() {
        val pseudo = _state.value.newPseudo
        if (pseudo.isBlank()) return
        // Clear the field up-front (synchronously): a fast double-tap then sees a blank field and is a
        // no-op, so we never issue two block() writes for the same pseudo. The new entry arrives via
        // observeEntries.
        _state.update { it.copy(newPseudo = "") }
        viewModelScope.launch {
            try {
                blacklistRepository.block(pseudo)
            } catch (_: IOException) {
                // Local DataStore write failed (rare). Restore the typed pseudo so it isn't lost — but
                // only if the user hasn't already started typing something else in the meantime.
                _state.update { if (it.newPseudo.isBlank()) it.copy(newPseudo = pseudo) else it }
            }
        }
    }
}
