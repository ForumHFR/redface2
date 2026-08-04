package fr.forumhfr.redface2.spike

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.model.BUILTIN_HFR_SMILEYS
import fr.forumhfr.redface2.core.ui.RedfaceTheme
import fr.forumhfr.redface2.core.ui.editor.SmileyCellDecoration
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerGrid
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerLayoutSpec
import fr.forumhfr.redface2.core.ui.editor.smileyGridGeometry

/** What the bench is currently showing — hoisted into the Activity so `adb` and the chips agree. */
internal data class SpikeUiState(
    val preset: SpikePreset = SPIKE_PRESETS.first(),
    /** #989 — index dans SmileyCellDecoration.entries : 0 aucun, 1 liseré, 2 séparateurs. */
    val decoration: Int = 0,
    val debug: Boolean = false,
    /**
     * Show the Standard tab's corpus (the ~25 builtins) instead of the perso one. The two tabs
     * SHARE this grid, so a geometry chosen for the persos also reshapes the builtins — which is
     * the #816 trap (« un seul 30 dp faisait les builtins gros+flous ET les persos à l'étroit »).
     * A wider cell leaves a 20 dp builtin sprite alone in ever more emptiness.
     */
    val builtin: Boolean = false,
)

/**
 * #989 — debug-only bench for the smiley picker's grid geometry, asked for by XaTriX (« fais des
 * spike app pour tester […] pour qu'on choisisse »).
 *
 * It renders the REAL [SmileyPickerGrid] — not a copy — under alternative
 * [SmileyPickerLayoutSpec]s, over the real perso corpus loaded from HFR by the app's own Coil
 * loader, so a screenshot shows what shipping the preset would actually give.
 *
 * Driving it from adb:
 * ```
 * adb shell am start -n <appId>/fr.forumhfr.redface2.spike.SmileyPickerSpikeActivity \
 *   --es preset E --ez outline true --ez debug false
 * ```
 * The state is held HERE and refreshed from [onNewIntent], which is load-bearing: `am start` on an
 * Activity already in the foreground brings the existing instance to front WITHOUT calling
 * `onCreate`, so reading the extras only in `onCreate` silently captured seven screenshots of the
 * same preset. Restarting with `am start -S` would fix the extras but empty the in-memory intrinsic
 * size cache, and the bench would then photograph the cold-cache fallback instead of the policy
 * (cadrage Sol, risque nº4) — keeping the process alive is the whole point.
 *
 * The preset chips sit BELOW a divider so they never enter the captured crop and never perturb the
 * grid's own geometry.
 */
class SmileyPickerSpikeActivity : ComponentActivity() {

    private var state by mutableStateOf(SpikeUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = state.applying(intent)
        setContent {
            RedfaceTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmileyPickerSpikeScreen(state = state, onState = { state = it })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        state = state.applying(intent)
    }

    /** Absent extras keep the current value, so a partial intent only moves what it names. */
    private fun SpikeUiState.applying(intent: Intent): SpikeUiState {
        val requested = intent.getStringExtra(EXTRA_PRESET)
        return copy(
            preset = SPIKE_PRESETS.firstOrNull { it.id == requested } ?: preset,
            decoration = intent.getIntExtra(EXTRA_DECORATION, decoration),
            debug = intent.getBooleanExtra(EXTRA_DEBUG, debug),
            builtin = intent.getBooleanExtra(EXTRA_BUILTIN, builtin),
        )
    }

    private companion object {
        const val EXTRA_PRESET = "preset"
        const val EXTRA_DECORATION = "deco"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_BUILTIN = "builtin"
    }
}

@Composable
private fun SmileyPickerSpikeScreen(state: SpikeUiState, onState: (SpikeUiState) -> Unit) {
    val decoration = SmileyCellDecoration.entries[state.decoration.coerceIn(0, SmileyCellDecoration.entries.lastIndex)]
    val spec = state.preset.spec.copy(
        cellDecoration = decoration,
        debugOverlay = state.debug,
        // Les séparateurs continus n'ont de sens qu'accolés : sans quoi ce sont des tirets. Mais
        // supprimer l'écart libère de la place et le solveur trouve alors 6 colonnes au lieu de 5 :
        // on relève donc minCellWidth pour comparer à géométrie ÉGALE, sinon on comparerait deux
        // grilles différentes et pas deux styles de délimitation.
        cellSpacing = if (decoration == SmileyCellDecoration.SEPARATORS) 0.dp else state.preset.spec.cellSpacing,
        minCellWidth = if (decoration == SmileyCellDecoration.SEPARATORS) 64.dp else state.preset.spec.minCellWidth,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // --- captured area: the sheet's own geometry, plus a caption to identify the shot ---------
        // The caption must quote the geometry the GRID resolved, so it is computed from the SAME
        // real constraint the grid sees — not from `LocalConfiguration.screenWidthDp` minus padding,
        // which ignores insets and could make the caption lie about the picture (gate Sol r2).
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spec.gridPadding, vertical = 8.dp),
        ) {
        val geometry = smileyGridGeometry(maxWidth, LocalDensity.current, spec)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${state.preset.id} · ${state.preset.label}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "%d col · cellule %.1f×%.1f · cap %.1f×%.1f · ×%.2f%s".format(
                    geometry.columns,
                    geometry.cellWidth.value,
                    geometry.cellHeight.value,
                    geometry.capWidth.value,
                    geometry.capHeight.value,
                    spec.persoScaleCeiling,
                    " · " + decoration.name.lowercase(),
                ) + if (state.builtin) " · onglet Standard" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SmileyPickerGrid(
                items = if (state.builtin) BUILTIN_HFR_SMILEYS else SPIKE_PERSO_CORPUS,
                onSmileyClicked = {},
                layout = spec,
            )
        }
        }

        // --- controls: below the divider, outside every capture crop ------------------------------
        HorizontalDivider()
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SPIKE_PRESETS.forEach { candidate ->
                    FilterChip(
                        selected = candidate.id == state.preset.id,
                        onClick = { onState(state.copy(preset = candidate)) },
                        label = { Text(candidate.id) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Déco", style = MaterialTheme.typography.labelMedium)
                Switch(
                    checked = state.decoration > 0,
                    onCheckedChange = { onState(state.copy(decoration = if (it) 1 else 0)) },
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                FilterChip(
                    selected = state.decoration == 2,
                    onClick = { onState(state.copy(decoration = if (state.decoration == 2) 0 else 2)) },
                    label = { Text("sépar.") },
                )
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text("Debug", style = MaterialTheme.typography.labelMedium)
                Switch(checked = state.debug, onCheckedChange = { onState(state.copy(debug = it)) })
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
                Text("Builtin", style = MaterialTheme.typography.labelMedium)
                Switch(checked = state.builtin, onCheckedChange = { onState(state.copy(builtin = it)) })
            }
            Text(
                text = state.preset.rationale,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
