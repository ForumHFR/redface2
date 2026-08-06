package fr.forumhfr.redface2.spike

import androidx.compose.ui.unit.dp
import fr.forumhfr.redface2.core.ui.editor.SmileyPickerLayoutSpec

/**
 * #989 — the candidate geometries put in front of XaTriX during the arbitration, one per question to
 * settle. **This list is a HISTORICAL RECORD of that arbitration, not a description of the shipped
 * picker.** The defaults moved twice since it was written (preset « E », then the target-cell policy
 * of the follow-up), so the dp figures in each `rationale` below describe the state at the time the
 * preset was proposed. The caption at the top of the bench always shows the geometry ACTUALLY
 * resolved by the current solver — trust it, not these strings. Retained because the reasoning
 * (what each candidate proved or refuted) is what makes the final choice auditable. Deliberately
 * NOT the cartesian product of the levers (cadrage Sol): the cell outline and the debug overlay are
 * capture toggles applied on top of any preset, not presets of their own.
 *
 * All the dp figures quoted below are for an S10e in portrait (360 dp wide) and were derived from
 * the same solver the grid uses. The « visible » counts are computed from the grid's height budget
 * (0.62 × 760 dp = 471 dp) — they are arithmetic, not counted off a screenshot, because the spike
 * adds a two-line caption above the grid that the real sheet replaces with its tabs.
 */
internal data class SpikePreset(
    val id: String,
    val label: String,
    val rationale: String,
    val spec: SmileyPickerLayoutSpec,
)

/** Shared basis of the compact presets: margins trimmed 16→8 dp and spacing 8→4 dp. */
private val COMPACT = SmileyPickerLayoutSpec(
    minCellWidth = 56.dp,
    gridPadding = 8.dp,
    cellSpacing = 4.dp,
)

/** The dominant perso format, 70×50 — a cell of this ratio wastes no vertical room on it. */
private const val PERSO_RATIO = 70f / 50f

internal val SPIKE_PRESETS: List<SpikePreset> = listOf(
    SpikePreset(
        id = "A",
        label = "Actuel (référence)",
        rationale = "Ce qui est livré : 6 col de 48 dp, cap 44. Le 70×50 dominant rend à 44×31, " +
            "les 17 dp de hauteur restants sont perdus. ~48 vignettes visibles.",
        spec = SmileyPickerLayoutSpec.Current,
    ),
    SpikePreset(
        id = "B",
        label = "5 colonnes, marges intactes",
        rationale = "Le levier « passer à 5 » seul : cellule 59,2 dp, cap 55, 70×50 → 55×39 " +
            "(+25 %). Coût : ~35 vignettes visibles au lieu de 48.",
        spec = SmileyPickerLayoutSpec(minCellWidth = 56.dp),
    ),
    SpikePreset(
        id = "C",
        label = "Marges rognées seules",
        rationale = "Le levier « rogner les marges » seul, à 6 colonnes : cellule 54 dp, cap 50, " +
            "70×50 → 50×36 (+14 %). Densité inchangée (~48 visibles) — le gain gratuit.",
        spec = SmileyPickerLayoutSpec(gridPadding = 8.dp, cellSpacing = 4.dp),
    ),
    SpikePreset(
        id = "D",
        label = "Les deux, cellule carrée",
        rationale = "5 col + marges rognées : cellule 65,6 dp, cap 61,6, 70×50 → 62×44 (+40 %). " +
            "Mais la cellule reste carrée, donc ~30 visibles seulement.",
        spec = COMPACT,
    ),
    SpikePreset(
        id = "E",
        label = "Les deux + cellule paysage",
        rationale = "Idem D mais la cellule prend le ratio 7:5 du corpus : 65,6×48 dp. Même " +
            "+40 % sur le 70×50, et ~45 visibles — le vertical n'est plus gâché.",
        spec = COMPACT.copy(cellAspectRatio = PERSO_RATIO),
    ),
    SpikePreset(
        id = "F",
        label = "E + upscale ×1,5",
        rationale = "Le seul preset qui traite la traîne des petits sprites : [:rofl] 39×15 passe " +
            "de 39×15 à 59×23, un 15×15 à 23×23. À rejeter si le flou se voit.",
        spec = COMPACT.copy(cellAspectRatio = PERSO_RATIO, persoScaleCeiling = 1.5f),
    ),
    SpikePreset(
        id = "G",
        label = "Borne haute — 4 colonnes",
        rationale = "Prouve ou réfute que le natif plein vaut la densité : cellule 83×59 dp, cap " +
            "79×55, le 70×50 rend à sa taille NATIVE. ~28 visibles, presque deux fois moins que A.",
        spec = COMPACT.copy(minCellWidth = 72.dp, cellAspectRatio = PERSO_RATIO),
    ),
)
