package fr.forumhfr.redface2.feature.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.forumhfr.redface2.core.domain.auth.AuthRepository
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocation
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageLocationStore
import fr.forumhfr.redface2.core.domain.mpstorage.MpStorageRepository
import fr.forumhfr.redface2.core.model.AuthState
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageFlagEntry
import fr.forumhfr.redface2.core.model.mpstorage.MpStorageResult
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * #6 — read-only debug inspector for the cross-app MPStorage MP. Surfaces what RF2 discovers and
 * parses (the same `fetchStorage()` the seeder uses), so a tester can confirm RF2 sees the same
 * container as DTCloud/MultiMP. No write/delete — purely diagnostic.
 */
sealed interface MpStorageInspectorUiState {
    data object Loading : MpStorageInspectorUiState

    /** No HFR session → the storage MP can't be fetched. */
    data object NotAuthenticated : MpStorageInspectorUiState

    /** Authenticated, but the inbox scan found no storage MP for this account. */
    data object NotFound : MpStorageInspectorUiState

    /** Storage MP located but its content is not parseable (non-JSON / invalid envelope). */
    data object Unreadable : MpStorageInspectorUiState

    /** Transport / unexpected failure while fetching. */
    data class Error(val message: String) : MpStorageInspectorUiState

    data class Loaded(
        val sourceName: String?,
        val location: MpStorageLocation?,
        val entries: List<MpStorageFlagEntry>,
        val rawEnvelope: String,
    ) : MpStorageInspectorUiState
}

@HiltViewModel
class MpStorageInspectorViewModel @Inject constructor(
    private val mpStorageRepository: MpStorageRepository,
    private val mpStorageLocationStore: MpStorageLocationStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<MpStorageInspectorUiState>(MpStorageInspectorUiState.Loading)
    val state: StateFlow<MpStorageInspectorUiState> = _state.asStateFlow()

    /** One fetch at a time; preempted on an owner change (see [load]). */
    private var loadJob: Job? = null

    /**
     * Owner (lowercased pseudo, or null = anonymous) the current state was loaded for. Tracked so a
     * mid-life logout / account switch — e.g. the inspector left in the back stack — re-scopes the
     * load and never keeps the PREVIOUS account's raw envelope on screen / copyable (Codex review).
     */
    private var currentOwner: String? = null
    private var initialized = false

    init {
        // React to auth changes for the whole ViewModel lifetime, not just once: the first emission
        // drives the initial load, and any later owner change (logout → null, or switch → other pseudo)
        // cancels the in-flight load and reloads for the new owner.
        viewModelScope.launch {
            authRepository.observeAuthState().collect { authState ->
                val owner = (authState as? AuthState.Authenticated)?.pseudo?.lowercase()
                if (!initialized || owner != currentOwner) {
                    initialized = true
                    currentOwner = owner
                    load(owner)
                }
            }
        }
    }

    /** Manual « Rafraîchir » — reloads for the current owner; a no-op while a load is in flight. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        load(currentOwner)
    }

    private fun load(owner: String?) {
        // Preempt any in-flight load so an account switch can't be overtaken by the previous owner's
        // fetch landing late, and clear the old state immediately.
        loadJob?.cancel()
        _state.value = MpStorageInspectorUiState.Loading
        loadJob = viewModelScope.launch {
            val next = if (owner == null) {
                // Anonymous can't fetch a private-message container, so short-circuit before the network.
                MpStorageInspectorUiState.NotAuthenticated
            } else {
                runCatching { mpStorageRepository.fetchStorage() }.fold(
                    onSuccess = { result ->
                        when (result) {
                            MpStorageResult.NotFound -> MpStorageInspectorUiState.NotFound
                            MpStorageResult.Unreadable -> MpStorageInspectorUiState.Unreadable
                            is MpStorageResult.Found -> MpStorageInspectorUiState.Loaded(
                                sourceName = result.document.sourceName,
                                // Read AFTER fetchStorage so a fresh discovery has already populated the cache.
                                location = mpStorageLocationStore.read(owner),
                                entries = result.document.mpFlags,
                                rawEnvelope = result.document.rawEnvelope,
                            )
                        }
                    },
                    onFailure = { error ->
                        // A superseding load (logout / account switch) cancelled us — propagate, never
                        // publish, and don't let runCatching map it to a bogus Error (Codex review).
                        if (error is CancellationException) throw error
                        MpStorageInspectorUiState.Error(error.message ?: error::class.simpleName ?: "erreur inconnue")
                    },
                )
            }
            // Publish ONLY if this load is still for the current owner. A later switch/logout has already
            // re-scoped `currentOwner` (and launched its own load), so a late completion of this one must
            // not clobber it with the previous account's data — the core of the cross-account leak guard
            // for the success race where cancellation arrives after the last suspension point (Codex review).
            if (owner == currentOwner) {
                _state.value = next
            }
        }
    }
}

/**
 * Read-only MPStorage inspector screen (debug). Hardcoded French labels, mirroring `DiagnosticsScreen`
 * (the other debug-grade utility) rather than going through `strings.xml`.
 */
@Composable
fun MpStorageInspectorScreen(onClose: () -> Unit) {
    val viewModel: MpStorageInspectorViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Inspecteur MP storage",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                (state as? MpStorageInspectorUiState.Loaded)?.let { loaded ->
                    TextButton(onClick = { copyEnvelopeToClipboard(context, loaded.rawEnvelope) }) {
                        Text("Copier")
                    }
                }
                TextButton(
                    enabled = state !is MpStorageInspectorUiState.Loading,
                    onClick = viewModel::refresh,
                ) { Text("Rafraîchir") }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (val s = state) {
                    MpStorageInspectorUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        InspectorBody("Lecture du MP storage…")
                    }
                    MpStorageInspectorUiState.NotAuthenticated ->
                        InspectorBody("Connecte-toi pour inspecter le MP storage.")
                    MpStorageInspectorUiState.NotFound ->
                        InspectorBody("Aucun MP de stockage trouvé (scan de la boîte MP infructueux).")
                    MpStorageInspectorUiState.Unreadable ->
                        InspectorBody("MP de stockage trouvé mais illisible (contenu non-JSON / invalide).")
                    is MpStorageInspectorUiState.Error ->
                        InspectorBody("Erreur : ${s.message}")
                    is MpStorageInspectorUiState.Loaded -> LoadedContent(s)
                }
            }
        }
    }
}

@Composable
private fun LoadedContent(state: MpStorageInspectorUiState.Loaded) {
    InspectorLabel("Source", state.sourceName ?: "inconnue")
    InspectorLabel(
        label = "Emplacement",
        value = state.location?.let { "thread ${it.threadId}, numrép ${it.numreponse}" } ?: "non caché",
    )
    InspectorLabel("Entrées DT", state.entries.size.toString())
    state.entries.forEach { entry ->
        Text(
            text = "• thread=${entry.threadId} page=${entry.page} " +
                "numrép=${entry.numreponse ?: "—"} uri=${entry.uri ?: "—"}",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(
        text = "Enveloppe brute",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = state.rawEnvelope.ifBlank { "(vide)" },
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun InspectorLabel(label: String, value: String) {
    Row {
        Text(
            text = "$label : ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun InspectorBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun copyEnvelopeToClipboard(context: Context, envelope: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("redface2 mpstorage", envelope))
}
